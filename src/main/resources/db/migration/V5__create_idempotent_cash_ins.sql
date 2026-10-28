-- A claim, its immutable receipt and its journal share one transaction. No pending claim may commit.
CREATE TABLE banking.cash_ins (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL CHECK (btrim(actor_issuer)<>''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject)<>''),
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    posted_at TIMESTAMPTZ,
    UNIQUE (actor_issuer,actor_subject,idempotency_key),
    CHECK ((journal_id IS NULL AND posted_at IS NULL) OR (journal_id IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE FUNCTION banking.cash_in_before_update() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF OLD.journal_id IS NOT NULL OR
       (NEW.id,NEW.actor_issuer,NEW.actor_subject,NEW.idempotency_key,NEW.request_hash,NEW.account_id,NEW.amount)
       IS DISTINCT FROM
       (OLD.id,OLD.actor_issuer,OLD.actor_subject,OLD.idempotency_key,OLD.request_hash,OLD.account_id,OLD.amount) THEN
        RAISE EXCEPTION 'Cash-in identity and completed receipts are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cash_in_immutable BEFORE UPDATE ON banking.cash_ins
FOR EACH ROW EXECUTE FUNCTION banking.cash_in_before_update();
CREATE TRIGGER cash_in_no_delete BEFORE DELETE ON banking.cash_ins
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER cash_in_no_truncate BEFORE TRUNCATE ON banking.cash_ins
EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.cash_in_require_posted() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE receipt banking.cash_ins%ROWTYPE;
BEGIN
    SELECT * INTO STRICT receipt FROM banking.cash_ins WHERE id=NEW.id;
    IF NOT EXISTS (
        SELECT 1 FROM banking.ledger_journals j
        WHERE j.id=receipt.journal_id AND j.reference_id=receipt.id
          AND j.status='POSTED' AND j.kind='SIMULATED_CASH_IN' AND j.currency='VND'
          AND j.actor_issuer=receipt.actor_issuer AND j.actor_subject=receipt.actor_subject
          AND j.posted_at=receipt.posted_at
    ) OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=receipt.journal_id) <> 2
    OR NOT EXISTS (
        SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=receipt.journal_id AND p.side='CREDIT' AND p.amount=receipt.amount
          AND a.bank_account_id=receipt.account_id AND a.account_type='LIABILITY' AND a.normal_side='CREDIT'
    ) OR NOT EXISTS (
        SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=receipt.journal_id AND p.side='DEBIT' AND p.amount=receipt.amount
          AND a.code='VND_CASH_CLEARING' AND a.bank_account_id IS NULL
          AND a.account_type='ASSET' AND a.normal_side='DEBIT'
    ) THEN
        RAISE EXCEPTION 'Cash-in requires its matching posted journal and clearing counterpart' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cash_in_posted_at_commit AFTER INSERT OR UPDATE ON banking.cash_ins
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.cash_in_require_posted();
