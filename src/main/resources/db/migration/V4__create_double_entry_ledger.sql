ALTER TABLE banking.accounts ADD CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency);

CREATE TABLE banking.ledger_accounts (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_side VARCHAR(6) NOT NULL CHECK (normal_side IN ('DEBIT','CREDIT')),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'VND'),
    bank_account_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, currency),
    FOREIGN KEY (bank_account_id, currency) REFERENCES banking.accounts(id, currency),
    CHECK ((account_type IN ('ASSET','EXPENSE') AND normal_side='DEBIT') OR
           (account_type IN ('LIABILITY','EQUITY','INCOME') AND normal_side='CREDIT')),
    CHECK (bank_account_id IS NULL OR account_type='LIABILITY')
);

-- Chart metadata only. No opening money or balance is seeded.
INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,created_at)
VALUES ('00000000-0000-0000-0000-000000000001','VND_CASH_CLEARING','Simulated cash clearing','ASSET','DEBIT','VND',now());

CREATE TABLE banking.ledger_journals (
    id UUID PRIMARY KEY,
    reference_id UUID NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('SIMULATED_CASH_IN','INTERNAL_TRANSFER','REVERSAL','ADJUSTMENT')),
    currency VARCHAR(3) NOT NULL CHECK (currency='VND'),
    status VARCHAR(8) NOT NULL CHECK (status IN ('DRAFT','POSTED')),
    actor_issuer VARCHAR(512) NOT NULL CHECK (btrim(actor_issuer)<>''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject)<>''),
    created_at TIMESTAMPTZ NOT NULL,
    posted_at TIMESTAMPTZ,
    UNIQUE (id,currency),
    CHECK ((status='DRAFT' AND posted_at IS NULL) OR (status='POSTED' AND posted_at IS NOT NULL AND posted_at>=created_at))
);

CREATE TABLE banking.ledger_postings (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency='VND'),
    side VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    -- No NUMERIC(p,0): PostgreSQL would round fractions before CHECK could reject them.
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    FOREIGN KEY (journal_id,currency) REFERENCES banking.ledger_journals(id,currency),
    FOREIGN KEY (ledger_account_id,currency) REFERENCES banking.ledger_accounts(id,currency)
);
CREATE INDEX ix_ledger_postings_journal ON banking.ledger_postings(journal_id);
CREATE INDEX ix_ledger_postings_account ON banking.ledger_postings(ledger_account_id,journal_id);

CREATE FUNCTION banking.ledger_balance(p_account UUID, p_draft UUID DEFAULT NULL) RETURNS NUMERIC
LANGUAGE sql VOLATILE SET search_path=pg_catalog,banking AS $$
    SELECT COALESCE(sum(CASE WHEN p.side=a.normal_side THEN p.amount ELSE -p.amount END),0)
    FROM banking.ledger_accounts a
    JOIN banking.ledger_postings p ON p.ledger_account_id=a.id
    JOIN banking.ledger_journals j ON j.id=p.journal_id
    WHERE a.id=p_account AND (j.status='POSTED' OR j.id=p_draft)
$$;

CREATE FUNCTION banking.ledger_reject_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    RAISE EXCEPTION 'Ledger records are append only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER ledger_account_immutable BEFORE UPDATE OR DELETE ON banking.ledger_accounts
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER ledger_posting_immutable BEFORE UPDATE OR DELETE ON banking.ledger_postings
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER ledger_accounts_no_truncate BEFORE TRUNCATE ON banking.ledger_accounts
EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER ledger_postings_no_truncate BEFORE TRUNCATE ON banking.ledger_postings
EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER ledger_journals_no_truncate BEFORE TRUNCATE ON banking.ledger_journals
EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.ledger_link_before_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE bank_state TEXT;
BEGIN
    IF NEW.bank_account_id IS NOT NULL THEN
        IF current_setting('transaction_isolation')<>'read committed' THEN
            RAISE EXCEPTION 'Ledger writes require read committed isolation' USING ERRCODE='23514';
        END IF;
        SELECT status INTO bank_state FROM banking.accounts WHERE id=NEW.bank_account_id FOR UPDATE;
        IF bank_state IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'Ledger linkage requires active account' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_link_validate BEFORE INSERT ON banking.ledger_accounts
FOR EACH ROW EXECUTE FUNCTION banking.ledger_link_before_insert();

CREATE FUNCTION banking.ledger_posting_before_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE journal_state TEXT;
BEGIN
    SELECT status INTO journal_state FROM banking.ledger_journals WHERE id=NEW.journal_id FOR UPDATE;
    IF journal_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Postings require an uncommitted draft journal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_posting_validate BEFORE INSERT ON banking.ledger_postings
FOR EACH ROW EXECUTE FUNCTION banking.ledger_posting_before_insert();

CREATE FUNCTION banking.ledger_journal_before_write() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE line_count BIGINT; distinct_accounts BIGINT; debits NUMERIC; credits NUMERIC; a RECORD; balance NUMERIC;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Ledger journal cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'DRAFT' THEN
            RAISE EXCEPTION 'Journal must start as draft' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status<>'DRAFT' OR NEW.status<>'POSTED' OR
       (to_jsonb(NEW)-'status'-'posted_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'posted_at') THEN
        RAISE EXCEPTION 'Only draft finalization is allowed' USING ERRCODE='23514';
    END IF;
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Ledger writes require read committed isolation' USING ERRCODE='23514';
    END IF;

    -- The same bank -> ledger lock order is used by the Java application contract.
    PERFORM b.id FROM banking.accounts b WHERE b.id IN (
        SELECT l.bank_account_id FROM banking.ledger_accounts l JOIN banking.ledger_postings p ON p.ledger_account_id=l.id
        WHERE p.journal_id=NEW.id AND l.bank_account_id IS NOT NULL) ORDER BY b.id FOR UPDATE;
    -- Posting FKs hold KEY SHARE on these immutable identities. NO KEY UPDATE serializes writers
    -- without deadlocking when another transaction already inserted its own draft postings.
    PERFORM l.id FROM banking.ledger_accounts l WHERE l.id IN (
        SELECT p.ledger_account_id FROM banking.ledger_postings p WHERE p.journal_id=NEW.id) ORDER BY l.id FOR NO KEY UPDATE;

    IF EXISTS (SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id
        JOIN banking.accounts b ON b.id=l.bank_account_id WHERE p.journal_id=NEW.id AND (b.status<>'ACTIVE' OR NOT b.ledger_provisioned)) THEN
        RAISE EXCEPTION 'Posting requires active provisioned account' USING ERRCODE='23514';
    END IF;
    SELECT count(*),count(DISTINCT ledger_account_id),COALESCE(sum(amount) FILTER(WHERE side='DEBIT'),0),
           COALESCE(sum(amount) FILTER(WHERE side='CREDIT'),0)
    INTO line_count,distinct_accounts,debits,credits FROM banking.ledger_postings WHERE journal_id=NEW.id;
    IF line_count<2 OR line_count>100 OR distinct_accounts<2 OR debits<>credits THEN
        RAISE EXCEPTION 'Journal must contain balanced postings on distinct accounts' USING ERRCODE='23514';
    END IF;
    FOR a IN SELECT l.id FROM banking.ledger_accounts l WHERE l.bank_account_id IS NOT NULL
        AND l.id IN (SELECT p.ledger_account_id FROM banking.ledger_postings p WHERE p.journal_id=NEW.id)
    LOOP
        balance := banking.ledger_balance(a.id,NEW.id);
        IF balance<0 OR balance>99999999999999999999 THEN
            RAISE EXCEPTION 'Customer ledger balance outside permitted range' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_journal_validate BEFORE INSERT OR UPDATE OR DELETE ON banking.ledger_journals
FOR EACH ROW EXECUTE FUNCTION banking.ledger_journal_before_write();

CREATE FUNCTION banking.ledger_require_posted_at_commit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE state TEXT;
BEGIN
    SELECT status INTO state FROM banking.ledger_journals WHERE id=NEW.id;
    IF state IS DISTINCT FROM 'POSTED' THEN
        RAISE EXCEPTION 'Draft journal cannot survive commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_journal_commit AFTER INSERT OR UPDATE ON banking.ledger_journals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.ledger_require_posted_at_commit();

CREATE FUNCTION banking.ledger_link_consistency() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE bank_id UUID; marked BOOLEAN; linked BOOLEAN;
BEGIN
    IF TG_TABLE_NAME='accounts' THEN bank_id:=NEW.id; ELSE bank_id:=NEW.bank_account_id; END IF;
    IF bank_id IS NULL THEN RETURN NULL; END IF;
    SELECT ledger_provisioned INTO marked FROM banking.accounts WHERE id=bank_id;
    SELECT EXISTS(SELECT 1 FROM banking.ledger_accounts WHERE bank_account_id=bank_id) INTO linked;
    IF marked IS DISTINCT FROM linked THEN
        RAISE EXCEPTION 'Ledger linkage and provisioning marker disagree' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_account_link_commit AFTER INSERT ON banking.ledger_accounts
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.ledger_link_consistency();
CREATE CONSTRAINT TRIGGER bank_account_link_commit AFTER INSERT OR UPDATE ON banking.accounts
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.ledger_link_consistency();

CREATE FUNCTION banking.ledger_account_state_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE ledger_id UUID;
BEGIN
    IF OLD.ledger_provisioned AND (NEW.id,NEW.customer_id,NEW.currency,NEW.account_number)
       IS DISTINCT FROM (OLD.id,OLD.customer_id,OLD.currency,OLD.account_number) THEN
        RAISE EXCEPTION 'Provisioned account identity is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.ledger_provisioned AND NOT NEW.ledger_provisioned THEN
        RAISE EXCEPTION 'Ledger provisioning marker cannot be reset' USING ERRCODE='23514';
    END IF;
    IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' AND OLD.ledger_provisioned THEN
        IF current_setting('transaction_isolation')<>'read committed' THEN
            RAISE EXCEPTION 'Financial close requires read committed isolation' USING ERRCODE='23514';
        END IF;
        SELECT id INTO ledger_id FROM banking.ledger_accounts WHERE bank_account_id=NEW.id;
        IF ledger_id IS NULL OR banking.ledger_balance(ledger_id)<>0 THEN
            RAISE EXCEPTION 'Account closure requires a zero authoritative ledger balance' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_bank_account_guard BEFORE UPDATE ON banking.accounts
FOR EACH ROW EXECUTE FUNCTION banking.ledger_account_state_guard();

DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM banking.accounts WHERE ledger_provisioned) THEN
        RAISE EXCEPTION 'Existing unbacked provisioning markers must be investigated before Ledger migration';
    END IF;
END $$;
