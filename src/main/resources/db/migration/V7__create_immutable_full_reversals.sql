CREATE TABLE banking.reversals (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL CHECK (btrim(actor_issuer)<>''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject)<>''),
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    original_journal_id UUID NOT NULL REFERENCES banking.ledger_journals(id) DEFERRABLE INITIALLY DEFERRED,
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('OPERATOR_ERROR','DUPLICATE_PROCESSING')),
    journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    original_kind VARCHAR(32) CHECK (original_kind IN ('SIMULATED_CASH_IN','INTERNAL_TRANSFER')),
    amount NUMERIC CHECK (amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    posted_at TIMESTAMPTZ,
    UNIQUE (actor_issuer,actor_subject,idempotency_key),
    CHECK (journal_id IS NULL OR journal_id<>original_journal_id),
    CHECK ((journal_id IS NULL AND posted_at IS NULL AND original_kind IS NULL AND amount IS NULL) OR
           (journal_id IS NOT NULL AND posted_at IS NOT NULL AND original_kind IS NOT NULL AND amount IS NOT NULL))
);
-- Pending claims from different keys may coexist inside uncommitted transactions. The original journal
-- NO KEY UPDATE lock serializes the use case; this index independently prevents two completed reversals.
CREATE UNIQUE INDEX uq_reversal_completed_original ON banking.reversals(original_journal_id) WHERE journal_id IS NOT NULL;

CREATE FUNCTION banking.reversal_before_update() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF OLD.journal_id IS NOT NULL OR
       (NEW.id,NEW.actor_issuer,NEW.actor_subject,NEW.idempotency_key,NEW.request_hash,NEW.original_journal_id,NEW.reason)
       IS DISTINCT FROM
       (OLD.id,OLD.actor_issuer,OLD.actor_subject,OLD.idempotency_key,OLD.request_hash,OLD.original_journal_id,OLD.reason) THEN
        RAISE EXCEPTION 'Reversal identity and completed receipts are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reversal_immutable BEFORE UPDATE ON banking.reversals
FOR EACH ROW EXECUTE FUNCTION banking.reversal_before_update();
CREATE TRIGGER reversal_no_delete BEFORE DELETE ON banking.reversals
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER reversal_no_truncate BEFORE TRUNCATE ON banking.reversals
EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.reversal_require_posted() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE receipt banking.reversals%ROWTYPE;
BEGIN
    SELECT * INTO STRICT receipt FROM banking.reversals WHERE id=NEW.id;
    IF NOT EXISTS (
        SELECT 1 FROM banking.ledger_journals original
        WHERE original.id=receipt.original_journal_id AND original.status='POSTED'
          AND original.kind=receipt.original_kind AND (
            (original.kind='SIMULATED_CASH_IN' AND EXISTS (SELECT 1 FROM banking.cash_ins c WHERE c.journal_id=original.id)) OR
            (original.kind='INTERNAL_TRANSFER' AND EXISTS (SELECT 1 FROM banking.transfers t WHERE t.journal_id=original.id)))
    ) OR NOT EXISTS (
        SELECT 1 FROM banking.ledger_journals inverse
        WHERE inverse.id=receipt.journal_id AND inverse.id<>receipt.original_journal_id
          AND inverse.reference_id=receipt.id AND inverse.kind='REVERSAL' AND inverse.status='POSTED'
          AND inverse.currency='VND' AND inverse.actor_issuer=receipt.actor_issuer
          AND inverse.actor_subject=receipt.actor_subject AND inverse.posted_at=receipt.posted_at
    ) OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=receipt.original_journal_id) <> 2
      OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=receipt.journal_id) <> 2
      OR EXISTS (
        SELECT 1 FROM banking.ledger_postings original
        WHERE original.journal_id=receipt.original_journal_id AND (
            original.amount<>receipt.amount OR NOT EXISTS (
                SELECT 1 FROM banking.ledger_postings inverse
                WHERE inverse.journal_id=receipt.journal_id AND inverse.ledger_account_id=original.ledger_account_id
                  AND inverse.currency=original.currency AND inverse.amount=original.amount
                  AND inverse.side=CASE WHEN original.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END))
    ) THEN
        RAISE EXCEPTION 'Reversal requires an eligible original and its exact full inverse' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reversal_posted_at_commit AFTER INSERT OR UPDATE ON banking.reversals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.reversal_require_posted();

CREATE FUNCTION banking.reversal_journal_require_receipt() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM banking.reversals WHERE journal_id=NEW.id) THEN
        RAISE EXCEPTION 'Reversal journal requires its atomic receipt' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reversal_journal_receipt_commit AFTER INSERT OR UPDATE ON banking.ledger_journals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.kind='REVERSAL')
EXECUTE FUNCTION banking.reversal_journal_require_receipt();
