INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,created_at)
VALUES('00000000-0000-0000-0000-000000000002','VND_TRANSFER_FEE_INCOME','Simulated transfer fee income','INCOME','CREDIT','VND',now());

CREATE TABLE banking.outgoing_policies(
    account_id UUID PRIMARY KEY REFERENCES banking.accounts(id),
    transaction_limit NUMERIC NOT NULL CHECK(transaction_limit>=0 AND transaction_limit=trunc(transaction_limit) AND transaction_limit<=99999999999999999999),
    daily_limit NUMERIC NOT NULL CHECK(daily_limit>=0 AND daily_limit=trunc(daily_limit) AND daily_limit<=99999999999999999999),
    fee NUMERIC NOT NULL CHECK(fee>=0 AND fee=trunc(fee) AND fee<=99999999999999999999),
    timezone TEXT NOT NULL
);
CREATE TABLE banking.outgoing_charges(
    journal_id UUID PRIMARY KEY REFERENCES banking.ledger_journals(id),
    account_id UUID NOT NULL REFERENCES banking.accounts(id),
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount=trunc(amount)),
    fee NUMERIC NOT NULL CHECK(fee>=0 AND fee=trunc(fee)),
    transaction_limit NUMERIC NOT NULL,
    daily_limit NUMERIC NOT NULL,
    timezone TEXT NOT NULL,
    business_date DATE NOT NULL,
    fee_journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    CHECK((fee=0 AND fee_journal_id IS NULL) OR (fee>0 AND fee_journal_id IS NOT NULL))
);
CREATE INDEX ix_outgoing_daily ON banking.outgoing_charges(account_id,business_date);

CREATE FUNCTION banking.outgoing_policy_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.timezone) THEN
        RAISE EXCEPTION 'Unknown policy timezone' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.account_id<>OLD.account_id OR NEW.timezone<>OLD.timezone) THEN
        RAISE EXCEPTION 'Policy identity and timezone cannot change' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' AND EXISTS(SELECT 1 FROM banking.outgoing_charges WHERE account_id=NEW.account_id AND timezone<>NEW.timezone) THEN
        RAISE EXCEPTION 'Policy timezone must preserve existing usage buckets' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outgoing_policy_check BEFORE INSERT OR UPDATE ON banking.outgoing_policies
FOR EACH ROW EXECUTE FUNCTION banking.outgoing_policy_validate();
CREATE TRIGGER outgoing_policy_no_delete BEFORE DELETE ON banking.outgoing_policies FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER outgoing_policy_no_truncate BEFORE TRUNCATE ON banking.outgoing_policies EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.outgoing_charge_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE p banking.outgoing_policies%ROWTYPE; j banking.ledger_journals%ROWTYPE; used NUMERIC;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Outgoing writes require read committed' USING ERRCODE='23514';
    END IF;
    PERFORM id FROM banking.accounts WHERE id=NEW.account_id FOR UPDATE;
    SELECT * INTO p FROM banking.outgoing_policies WHERE account_id=NEW.account_id FOR SHARE;
    IF NOT FOUND THEN
        p.transaction_limit:=99999999999999999999; p.daily_limit:=99999999999999999999;
        p.fee:=0; p.timezone:='Asia/Ho_Chi_Minh';
    END IF;
    IF (NEW.transaction_limit,NEW.daily_limit,NEW.fee,NEW.timezone) IS DISTINCT FROM
       (p.transaction_limit,p.daily_limit,p.fee,p.timezone) THEN
        RAISE EXCEPTION 'Outgoing policy snapshot mismatch' USING ERRCODE='23514';
    END IF;
    SELECT * INTO j FROM banking.ledger_journals WHERE id=NEW.journal_id;
    IF j.status IS DISTINCT FROM 'POSTED' OR j.kind IS DISTINCT FROM 'INTERNAL_TRANSFER'
    OR NEW.business_date IS DISTINCT FROM (j.posted_at AT TIME ZONE NEW.timezone)::date
    OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=j.id)<>2
    OR NOT EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
        WHERE l.journal_id=j.id AND a.bank_account_id=NEW.account_id AND l.side='DEBIT' AND l.amount=NEW.amount) THEN
        RAISE EXCEPTION 'Charge requires exact posted principal' USING ERRCODE='23514';
    END IF;
    SELECT COALESCE(sum(amount+fee),0) INTO used FROM banking.outgoing_charges
        WHERE account_id=NEW.account_id AND business_date=NEW.business_date;
    IF NEW.amount+NEW.fee>NEW.transaction_limit OR used+NEW.amount+NEW.fee>NEW.daily_limit THEN
        RAISE EXCEPTION 'Outgoing limit exceeded' USING ERRCODE='23514';
    END IF;
    IF NEW.fee>0 AND (NOT EXISTS(SELECT 1 FROM banking.ledger_journals f WHERE f.id=NEW.fee_journal_id
        AND f.status='POSTED' AND f.kind='ADJUSTMENT' AND f.actor_issuer=j.actor_issuer AND f.actor_subject=j.actor_subject)
    OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=NEW.fee_journal_id)<>2
    OR NOT EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
        WHERE l.journal_id=NEW.fee_journal_id AND a.bank_account_id=NEW.account_id AND l.side='DEBIT' AND l.amount=NEW.fee)
    OR NOT EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
        WHERE l.journal_id=NEW.fee_journal_id AND a.code='VND_TRANSFER_FEE_INCOME' AND l.side='CREDIT' AND l.amount=NEW.fee)) THEN
        RAISE EXCEPTION 'Charge requires exact balanced fee journal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outgoing_charge_check BEFORE INSERT ON banking.outgoing_charges
FOR EACH ROW EXECUTE FUNCTION banking.outgoing_charge_validate();
CREATE TRIGGER outgoing_charge_no_update BEFORE UPDATE OR DELETE ON banking.outgoing_charges
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER outgoing_charge_no_truncate BEFORE TRUNCATE ON banking.outgoing_charges EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.outgoing_require_assessment() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
        JOIN banking.outgoing_policies p ON p.account_id=a.bank_account_id
        WHERE l.journal_id=NEW.id AND l.side='DEBIT')
        AND NOT EXISTS(SELECT 1 FROM banking.outgoing_charges WHERE journal_id=NEW.id) THEN
        RAISE EXCEPTION 'Configured outgoing principal requires atomic assessment' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER outgoing_assessment_commit AFTER INSERT OR UPDATE ON banking.ledger_journals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.kind='INTERNAL_TRANSFER')
EXECUTE FUNCTION banking.outgoing_require_assessment();
