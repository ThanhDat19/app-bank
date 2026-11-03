-- Preserve pre-cutover facts; never invent historical remote decisions.
-- Serialize the legacy inventory and trigger installation with concurrent transfer writers.
LOCK TABLE banking.transfers IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE banking.transfer_risk_legacy(
    transfer_id UUID PRIMARY KEY REFERENCES banking.transfers(id) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO banking.transfer_risk_legacy SELECT id FROM banking.transfers;
CREATE TRIGGER transfer_risk_legacy_immutable BEFORE INSERT OR UPDATE OR DELETE ON banking.transfer_risk_legacy
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER transfer_risk_legacy_no_truncate BEFORE TRUNCATE ON banking.transfer_risk_legacy
EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE TABLE banking.transfer_risk_decisions(
    transfer_id UUID PRIMARY KEY REFERENCES banking.transfers(id) DEFERRABLE INITIALLY DEFERRED,
    request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    request_id CHAR(64) NOT NULL CHECK(request_id ~ '^[a-f0-9]{64}$'),
    outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('APPROVED','BYPASSED')),
    policy_version VARCHAR(64) NOT NULL CHECK(policy_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    reason VARCHAR(32) NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CHECK((outcome='BYPASSED' AND policy_version='disabled-v1' AND reason='RISK_DISABLED' AND expires_at IS NULL)
       OR (outcome='APPROVED' AND reason='AMOUNT_WITHIN_LIMIT' AND expires_at>assessed_at AND expires_at IS NOT NULL))
);
CREATE FUNCTION banking.transfer_risk_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM banking.transfer_risk_legacy WHERE transfer_id=NEW.transfer_id)
    OR NOT EXISTS(SELECT 1 FROM banking.transfers t JOIN banking.ledger_journals j ON j.id=t.journal_id
                  WHERE t.id=NEW.transfer_id AND t.request_hash=NEW.request_hash AND j.status='POSTED') THEN
        RAISE EXCEPTION 'Risk receipt requires its matching new posted transfer' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transfer_risk_check BEFORE INSERT ON banking.transfer_risk_decisions
FOR EACH ROW EXECUTE FUNCTION banking.transfer_risk_validate();
CREATE TRIGGER transfer_risk_immutable BEFORE UPDATE OR DELETE ON banking.transfer_risk_decisions
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER transfer_risk_no_truncate BEFORE TRUNCATE ON banking.transfer_risk_decisions
EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE FUNCTION banking.transfer_require_risk() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.transfer_risk_legacy WHERE transfer_id=NEW.id)
       AND NOT EXISTS(SELECT 1 FROM banking.transfer_risk_decisions d JOIN banking.transfers t ON t.id=d.transfer_id
                      WHERE t.id=NEW.id AND d.request_hash=t.request_hash) THEN
        RAISE EXCEPTION 'New transfer requires an atomic risk policy receipt' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_risk_at_commit AFTER INSERT OR UPDATE ON banking.transfers
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.transfer_require_risk();
