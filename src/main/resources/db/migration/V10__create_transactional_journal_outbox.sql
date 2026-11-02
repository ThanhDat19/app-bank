CREATE TABLE banking.outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL UNIQUE REFERENCES banking.ledger_journals(id),
    aggregate_type TEXT NOT NULL CHECK(aggregate_type='LedgerJournal'),
    aggregate_version BIGINT NOT NULL CHECK(aggregate_version=1),
    event_type TEXT NOT NULL CHECK(event_type='LedgerJournalPosted'),
    schema_version INTEGER NOT NULL CHECK(schema_version=1),
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
CREATE TABLE banking.outbox_deliveries (
    event_id UUID PRIMARY KEY REFERENCES banking.outbox_events(event_id),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','DELIVERED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    CHECK((state='LEASED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND delivered_at IS NULL)
       OR (state='PENDING' AND claim_token IS NULL AND lease_until IS NULL AND delivered_at IS NULL)
       OR (state='DELIVERED' AND claim_token IS NULL AND lease_until IS NULL AND delivered_at IS NOT NULL))
);
CREATE INDEX ix_outbox_ready ON banking.outbox_deliveries(available_at,event_id) WHERE state='PENDING';
CREATE INDEX ix_outbox_expired ON banking.outbox_deliveries(lease_until,event_id) WHERE state='LEASED';

CREATE FUNCTION banking.journal_event_payload(journal UUID) RETURNS JSONB
LANGUAGE sql STABLE SET search_path=pg_catalog,banking AS $$
    SELECT jsonb_build_object('journalId',j.id,'referenceId',j.reference_id,'kind',j.kind,'currency',j.currency,
        'postings',(SELECT jsonb_agg(jsonb_build_object('ledgerAccountId',p.ledger_account_id,'side',p.side,
            'amount',trunc(p.amount)::text) ORDER BY p.id) FROM banking.ledger_postings p WHERE p.journal_id=j.id))
    FROM banking.ledger_journals j WHERE j.id=journal
$$;
CREATE FUNCTION banking.outbox_event_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.ledger_journals j WHERE j.id=NEW.aggregate_id
        AND j.status='POSTED' AND j.posted_at=NEW.occurred_at)
        OR NEW.payload IS DISTINCT FROM banking.journal_event_payload(NEW.aggregate_id) THEN
        RAISE EXCEPTION 'Event must match its posted journal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outbox_event_check BEFORE INSERT ON banking.outbox_events
FOR EACH ROW EXECUTE FUNCTION banking.outbox_event_validate();
CREATE TRIGGER outbox_event_immutable BEFORE UPDATE OR DELETE ON banking.outbox_events
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER outbox_event_no_truncate BEFORE TRUNCATE ON banking.outbox_events EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER outbox_delivery_no_delete BEFORE DELETE ON banking.outbox_deliveries
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER outbox_delivery_no_truncate BEFORE TRUNCATE ON banking.outbox_deliveries EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.outbox_enqueue() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE event UUID;
BEGIN
    INSERT INTO banking.outbox_events(aggregate_id,aggregate_type,aggregate_version,event_type,schema_version,occurred_at,payload)
    VALUES(NEW.id,'LedgerJournal',1,'LedgerJournalPosted',1,NEW.posted_at,banking.journal_event_payload(NEW.id))
    RETURNING event_id INTO event;
    INSERT INTO banking.outbox_deliveries(event_id) VALUES(event);
    RETURN NULL;
END $$;
CREATE TRIGGER journal_outbox AFTER UPDATE ON banking.ledger_journals
FOR EACH ROW WHEN(OLD.status='DRAFT' AND NEW.status='POSTED') EXECUTE FUNCTION banking.outbox_enqueue();

CREATE FUNCTION banking.outbox_delivery_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PENDING' OR NEW.attempts<>0 THEN
            RAISE EXCEPTION 'Delivery must start pending' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.event_id<>OLD.event_id OR OLD.state='DELIVERED' THEN
            RAISE EXCEPTION 'Delivery identity and completed state are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.state='LEASED' THEN
            IF NEW.attempts<>OLD.attempts+1 OR NEW.lease_until<=clock_timestamp()
                OR NEW.claim_token IS NOT DISTINCT FROM OLD.claim_token
                OR (OLD.state='LEASED' AND OLD.lease_until>clock_timestamp())
                OR (OLD.state='PENDING' AND OLD.available_at>clock_timestamp()) THEN
                RAISE EXCEPTION 'Invalid delivery claim' USING ERRCODE='23514';
            END IF;
        ELSIF OLD.state<>'LEASED' OR NEW.attempts<>OLD.attempts OR OLD.lease_until<=clock_timestamp() THEN
            RAISE EXCEPTION 'Completion requires a live lease' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outbox_delivery_check BEFORE INSERT OR UPDATE ON banking.outbox_deliveries
FOR EACH ROW EXECUTE FUNCTION banking.outbox_delivery_validate();
