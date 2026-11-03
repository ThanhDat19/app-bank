CREATE TABLE banking.notification_inbox(
    event_id UUID PRIMARY KEY REFERENCES banking.outbox_events(event_id),
    payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE banking.notifications(
    event_id UUID NOT NULL REFERENCES banking.notification_inbox(event_id),
    account_id UUID NOT NULL REFERENCES banking.accounts(id),
    journal_id UUID NOT NULL REFERENCES banking.ledger_journals(id),
    kind VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(event_id,account_id)
);
CREATE INDEX ix_notifications_account_time ON banking.notifications(account_id,occurred_at DESC,event_id DESC);
CREATE TABLE banking.notification_failures(
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK(source_partition>=0),
    source_offset BIGINT NOT NULL CHECK(source_offset>=0),
    payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),
    attempts INTEGER NOT NULL CHECK(attempts>0),
    available_at TIMESTAMPTZ NOT NULL,
    quarantined BOOLEAN NOT NULL DEFAULT false,
    error_code VARCHAR(32) NOT NULL CHECK(error_code IN ('INVALID_EVENT','PROCESSING_FAILED')),
    PRIMARY KEY(source_topic,source_partition,source_offset)
);
CREATE FUNCTION banking.notification_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.outbox_events e JOIN banking.ledger_journals j ON j.id=e.aggregate_id
        JOIN banking.ledger_postings p ON p.journal_id=j.id JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id
        WHERE e.event_id=NEW.event_id AND j.id=NEW.journal_id AND j.kind=NEW.kind
        AND j.posted_at=NEW.occurred_at AND l.bank_account_id=NEW.account_id) THEN
        RAISE EXCEPTION 'Notification must match a posted journal target' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER notification_check BEFORE INSERT ON banking.notifications FOR EACH ROW EXECUTE FUNCTION banking.notification_validate();
CREATE TRIGGER notification_immutable BEFORE UPDATE OR DELETE ON banking.notifications FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER notification_no_truncate BEFORE TRUNCATE ON banking.notifications EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER notification_inbox_immutable BEFORE UPDATE OR DELETE ON banking.notification_inbox FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER notification_inbox_no_truncate BEFORE TRUNCATE ON banking.notification_inbox EXECUTE FUNCTION banking.ledger_reject_mutation();
