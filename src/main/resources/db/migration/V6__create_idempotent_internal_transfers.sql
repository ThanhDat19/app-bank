CREATE TABLE banking.transfers (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL CHECK (btrim(actor_issuer)<>''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject)<>''),
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    source_account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    destination_account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    posted_at TIMESTAMPTZ,
    UNIQUE (actor_issuer,actor_subject,idempotency_key),
    CHECK (source_account_id<>destination_account_id),
    CHECK ((journal_id IS NULL AND posted_at IS NULL) OR (journal_id IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE FUNCTION banking.transfer_before_update() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF OLD.journal_id IS NOT NULL OR
       (NEW.id,NEW.actor_issuer,NEW.actor_subject,NEW.idempotency_key,NEW.request_hash,NEW.source_account_id,NEW.destination_account_id,NEW.amount)
       IS DISTINCT FROM
       (OLD.id,OLD.actor_issuer,OLD.actor_subject,OLD.idempotency_key,OLD.request_hash,OLD.source_account_id,OLD.destination_account_id,OLD.amount) THEN
        RAISE EXCEPTION 'Transfer identity and completed receipts are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transfer_immutable BEFORE UPDATE ON banking.transfers
FOR EACH ROW EXECUTE FUNCTION banking.transfer_before_update();
CREATE TRIGGER transfer_no_delete BEFORE DELETE ON banking.transfers
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER transfer_no_truncate BEFORE TRUNCATE ON banking.transfers
EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.transfer_require_posted() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE receipt banking.transfers%ROWTYPE;
BEGIN
    SELECT * INTO STRICT receipt FROM banking.transfers WHERE id=NEW.id;
    IF NOT EXISTS (
        SELECT 1 FROM banking.ledger_journals j
        WHERE j.id=receipt.journal_id AND j.reference_id=receipt.id AND j.status='POSTED'
          AND j.kind='INTERNAL_TRANSFER' AND j.currency='VND' AND j.posted_at=receipt.posted_at
          AND j.actor_issuer=receipt.actor_issuer AND j.actor_subject=receipt.actor_subject
    ) OR NOT EXISTS (
        SELECT 1 FROM banking.accounts a JOIN banking.customers c ON c.id=a.customer_id
        WHERE a.id=receipt.source_account_id AND c.identity_issuer=receipt.actor_issuer AND c.identity_subject=receipt.actor_subject
    ) OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=receipt.journal_id) <> 2
    OR NOT EXISTS (
        SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=receipt.journal_id AND p.side='DEBIT' AND p.amount=receipt.amount
          AND a.bank_account_id=receipt.source_account_id AND a.account_type='LIABILITY' AND a.normal_side='CREDIT'
    ) OR NOT EXISTS (
        SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=receipt.journal_id AND p.side='CREDIT' AND p.amount=receipt.amount
          AND a.bank_account_id=receipt.destination_account_id AND a.account_type='LIABILITY' AND a.normal_side='CREDIT'
    ) THEN
        RAISE EXCEPTION 'Transfer requires an owned source and its matching posted debit and credit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_posted_at_commit AFTER INSERT OR UPDATE ON banking.transfers
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.transfer_require_posted();
