CREATE TABLE banking.external_payments(
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    settlement_account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    beneficiary VARCHAR(44) NOT NULL CHECK(beneficiary ~ '^SIM_[A-Z0-9_-]{1,40}$'),
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    reserved_amount NUMERIC NOT NULL CHECK(reserved_amount>=amount AND reserved_amount=trunc(reserved_amount) AND reserved_amount<=99999999999999999999),
    partner_instance UUID NOT NULL,
    risk_outcome VARCHAR(16) NOT NULL CHECK(risk_outcome IN ('APPROVED','BYPASSED')),
    risk_policy VARCHAR(64) NOT NULL CHECK(risk_policy ~ '^[A-Za-z0-9._-]{1,64}$'),
    risk_reason VARCHAR(32) NOT NULL,
    risk_assessed_at TIMESTAMPTZ NOT NULL,
    risk_expires_at TIMESTAMPTZ,
    state VARCHAR(16) NOT NULL CHECK(state IN ('CREATING','PREPARED','UNKNOWN','SETTLING','SUCCEEDED','FAILED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    partner_version BIGINT NOT NULL DEFAULT 0 CHECK(partner_version BETWEEN 0 AND 2),
    partner_state VARCHAR(16) CHECK(partner_state IN ('PENDING','SUCCEEDED','FAILED')),
    problem VARCHAR(64) CHECK(problem ~ '^EXTERNAL_[A-Z_]{1,54}$'),
    journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(actor_issuer,actor_subject,idempotency_key),
    CHECK(account_id<>settlement_account_id),
    CHECK((partner_version=0 AND partner_state IS NULL) OR (partner_version=1 AND partner_state IS NOT DISTINCT FROM 'PENDING') OR (partner_version=2 AND partner_state IS NOT NULL AND partner_state IN ('SUCCEEDED','FAILED'))),
    CHECK((risk_outcome='BYPASSED' AND risk_policy='disabled-v1' AND risk_reason='RISK_DISABLED' AND risk_expires_at IS NULL)
       OR (risk_outcome='APPROVED' AND risk_reason='AMOUNT_WITHIN_LIMIT' AND risk_expires_at>risk_assessed_at AND risk_expires_at IS NOT NULL)),
    CHECK((state='SUCCEEDED' AND journal_id IS NOT NULL AND partner_state IS NOT DISTINCT FROM 'SUCCEEDED' AND partner_version=2) OR (state<>'SUCCEEDED' AND journal_id IS NULL)),
    CHECK(state<>'FAILED' OR (partner_state IS NOT DISTINCT FROM 'FAILED' AND partner_version=2)),
    CHECK(state NOT IN ('CREATING','PREPARED','CANCELLED') OR partner_version=0)
);
CREATE INDEX ix_external_reservations ON banking.external_payments(account_id) WHERE state IN ('PREPARED','UNKNOWN');
CREATE INDEX ix_external_pending_settlement ON banking.external_payments(settlement_account_id) WHERE state IN ('PREPARED','UNKNOWN');
CREATE FUNCTION banking.has_external_pending(p_account UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path=pg_catalog,banking AS $$
    SELECT EXISTS(SELECT 1 FROM banking.external_payments WHERE (account_id=p_account OR settlement_account_id=p_account) AND state IN ('PREPARED','UNKNOWN'))
$$;
CREATE FUNCTION banking.external_closure_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' THEN
        IF current_setting('transaction_isolation')<>'read committed' OR banking.has_external_pending(NEW.id) THEN
            RAISE EXCEPTION 'Account has unresolved external commands or unsafe isolation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER external_account_close BEFORE UPDATE ON banking.accounts FOR EACH ROW EXECUTE FUNCTION banking.external_closure_guard();

-- Stable snapshot helpers for API reads; volatile wrapper remains the writer's after-lock clock read.
CREATE FUNCTION banking.reserved_balance_at(p_account UUID,p_at TIMESTAMPTZ) RETURNS NUMERIC
LANGUAGE sql STABLE SET search_path=pg_catalog,banking AS $$
    SELECT COALESCE((SELECT sum(amount) FROM banking.holds WHERE account_id=p_account AND state='ACTIVE' AND expires_at>p_at),0)
         + COALESCE((SELECT sum(reserved_amount) FROM banking.external_payments WHERE account_id=p_account AND state IN ('PREPARED','UNKNOWN')),0)
$$;
CREATE OR REPLACE FUNCTION banking.reserved_balance(p_account UUID) RETURNS NUMERIC
LANGUAGE sql VOLATILE SET search_path=pg_catalog,banking AS $$
    SELECT banking.reserved_balance_at(p_account,clock_timestamp())
$$;
CREATE FUNCTION banking.reservation_count_at(p_account UUID,p_at TIMESTAMPTZ) RETURNS BIGINT
LANGUAGE sql STABLE SET search_path=pg_catalog,banking AS $$
    SELECT (SELECT count(*) FROM banking.holds WHERE account_id=p_account AND state='ACTIVE' AND expires_at>p_at)
         + (SELECT count(*) FROM banking.external_payments WHERE account_id=p_account AND state IN ('PREPARED','UNKNOWN'))
$$;

CREATE TABLE banking.external_payment_history(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES banking.external_payments(id),
    version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    partner_version BIGINT NOT NULL,
    partner_state VARCHAR(16),
    problem VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(payment_id,version)
);
CREATE FUNCTION banking.external_before_write() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE ledger_id UUID; quoted_fee NUMERIC;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'External writes require read committed' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'CREATING' OR NEW.version<>0 OR NEW.partner_version<>0 OR NEW.journal_id IS NOT NULL THEN
            RAISE EXCEPTION 'External payment must start creating' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW)-'state'-'version'-'partner_version'-'partner_state'-'problem'-'journal_id'-'updated_at'-'reserved_amount') IS DISTINCT FROM
       (to_jsonb(OLD)-'state'-'version'-'partner_version'-'partner_state'-'problem'-'journal_id'-'updated_at'-'reserved_amount')
       OR NEW.version<>OLD.version+1 OR NEW.partner_version<OLD.partner_version
       OR (OLD.state<>'CREATING' AND NEW.reserved_amount<>OLD.reserved_amount)
       OR (OLD.partner_state IN ('SUCCEEDED','FAILED') AND NEW.partner_state IS DISTINCT FROM OLD.partner_state)
       OR NOT ((OLD.state='CREATING' AND NEW.state='PREPARED') OR
               (OLD.state='PREPARED' AND NEW.state IN ('UNKNOWN','CANCELLED')) OR
               (OLD.state='UNKNOWN' AND NEW.state IN ('UNKNOWN','SETTLING','FAILED')) OR
               (OLD.state='SETTLING' AND NEW.state='SUCCEEDED')) THEN
        RAISE EXCEPTION 'Invalid external mutation' USING ERRCODE='23514';
    END IF;
    -- Same bank lock order as all existing pair writers; FK locks are deferred until commit.
    PERFORM id FROM banking.accounts WHERE id IN (NEW.account_id,NEW.settlement_account_id) ORDER BY id FOR UPDATE;
    IF NEW.state='PREPARED' OR (OLD.state='PREPARED' AND NEW.state='UNKNOWN') THEN
        IF NOT EXISTS(SELECT 1 FROM banking.accounts a JOIN banking.customers c ON c.id=a.customer_id
            WHERE a.id=NEW.account_id AND a.status='ACTIVE' AND a.ledger_provisioned
            AND c.identity_issuer=NEW.actor_issuer AND c.identity_subject=NEW.actor_subject)
        OR NOT EXISTS(SELECT 1 FROM banking.accounts WHERE id=NEW.settlement_account_id AND status='ACTIVE' AND ledger_provisioned) THEN
            RAISE EXCEPTION 'External source must be owned active and provisioned' USING ERRCODE='23514';
        END IF;
        SELECT id INTO ledger_id FROM banking.ledger_accounts WHERE bank_account_id=NEW.account_id;
        SELECT fee INTO quoted_fee FROM banking.outgoing_policies WHERE account_id=NEW.account_id FOR SHARE;
        IF (NEW.state='PREPARED' AND (NEW.reserved_amount<>NEW.amount+COALESCE(quoted_fee,0)
           OR banking.ledger_balance(ledger_id)-banking.reserved_balance(NEW.account_id)<NEW.reserved_amount))
           OR (NEW.state='UNKNOWN' AND (NEW.amount+COALESCE(quoted_fee,0)>NEW.reserved_amount
           OR banking.ledger_balance(ledger_id)<banking.reserved_balance(NEW.account_id))) THEN
            RAISE EXCEPTION 'External reservation exceeds available money or quote' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER external_validate BEFORE INSERT OR UPDATE ON banking.external_payments FOR EACH ROW EXECUTE FUNCTION banking.external_before_write();
CREATE TRIGGER external_no_delete BEFORE DELETE ON banking.external_payments FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER external_no_truncate BEFORE TRUNCATE ON banking.external_payments EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.external_history_append() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.state NOT IN ('CREATING','SETTLING') THEN
        INSERT INTO banking.external_payment_history(payment_id,version,state,partner_version,partner_state,problem)
        VALUES(NEW.id,NEW.version,NEW.state,NEW.partner_version,NEW.partner_state,NEW.problem);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER external_history AFTER UPDATE ON banking.external_payments FOR EACH ROW EXECUTE FUNCTION banking.external_history_append();
CREATE FUNCTION banking.external_history_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.external_payments p WHERE p.id=NEW.payment_id AND p.version=NEW.version
        AND p.state=NEW.state AND p.state NOT IN ('CREATING','SETTLING') AND p.partner_version=NEW.partner_version
        AND p.partner_state IS NOT DISTINCT FROM NEW.partner_state AND p.problem IS NOT DISTINCT FROM NEW.problem) THEN
        RAISE EXCEPTION 'History must reflect its current command transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER external_history_check BEFORE INSERT ON banking.external_payment_history FOR EACH ROW EXECUTE FUNCTION banking.external_history_validate();
CREATE TRIGGER external_history_immutable BEFORE UPDATE OR DELETE ON banking.external_payment_history FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER external_history_no_truncate BEFORE TRUNCATE ON banking.external_payment_history EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.external_at_commit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE p banking.external_payments%ROWTYPE;
BEGIN
    SELECT * INTO STRICT p FROM banking.external_payments WHERE id=NEW.id;
    IF p.state IN ('CREATING','SETTLING') OR NOT EXISTS(SELECT 1 FROM banking.external_payment_history h
        WHERE h.payment_id=p.id AND h.version=p.version AND h.state=p.state AND h.partner_version=p.partner_version
        AND h.partner_state IS NOT DISTINCT FROM p.partner_state AND h.problem IS NOT DISTINCT FROM p.problem) THEN
        RAISE EXCEPTION 'External command must be finalized with history' USING ERRCODE='23514';
    END IF;
    IF p.state='SUCCEEDED' AND (NOT EXISTS(SELECT 1 FROM banking.ledger_journals j
        WHERE j.id=p.journal_id AND j.reference_id=p.id AND j.status='POSTED' AND j.kind='INTERNAL_TRANSFER'
        AND j.actor_issuer=p.actor_issuer AND j.actor_subject=p.actor_subject)
        OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=p.journal_id)<>2
        OR NOT EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
            WHERE l.journal_id=p.journal_id AND a.bank_account_id=p.account_id AND l.side='DEBIT' AND l.amount=p.amount)
        OR NOT EXISTS(SELECT 1 FROM banking.ledger_postings l JOIN banking.ledger_accounts a ON a.id=l.ledger_account_id
            WHERE l.journal_id=p.journal_id AND a.bank_account_id=p.settlement_account_id AND l.side='CREDIT' AND l.amount=p.amount)
        OR NOT EXISTS(SELECT 1 FROM banking.outgoing_charges WHERE journal_id=p.journal_id AND account_id=p.account_id AND amount=p.amount)) THEN
        RAISE EXCEPTION 'External success requires its exact atomic clearing transfer' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER external_commit AFTER INSERT OR UPDATE ON banking.external_payments
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.external_at_commit();

-- Preserve all V12 diagnostics; include external reservations in the existing reservation rule.
CREATE OR REPLACE FUNCTION banking.reconciliation_scan(p_snapshot TIMESTAMPTZ,p_grace INTEGER)
RETURNS TABLE(code TEXT,severity TEXT,resource_type TEXT,resource_id TEXT)
LANGUAGE sql STABLE SET search_path=pg_catalog,banking AS $$
WITH journal_totals AS (
    SELECT j.id,j.status,count(p.id) lines,count(DISTINCT p.ledger_account_id) accounts,
        COALESCE(sum(CASE WHEN p.side='DEBIT' THEN p.amount ELSE -p.amount END),0) delta,
        bool_and(p.currency=j.currency) currency_ok
    FROM banking.ledger_journals j LEFT JOIN banking.ledger_postings p ON p.journal_id=j.id GROUP BY j.id
), balances AS (
    SELECT l.id,l.bank_account_id,COALESCE(sum(CASE WHEN j.status='POSTED' THEN
        CASE WHEN p.side=l.normal_side THEN p.amount ELSE -p.amount END ELSE 0 END),0) balance
    FROM banking.ledger_accounts l LEFT JOIN banking.ledger_postings p ON p.ledger_account_id=l.id
    LEFT JOIN banking.ledger_journals j ON j.id=p.journal_id GROUP BY l.id
), expected_notifications AS (
    SELECT DISTINCT e.event_id,e.aggregate_id,l.bank_account_id,e.occurred_at,j.kind
    FROM banking.notification_inbox i JOIN banking.outbox_events e USING(event_id)
    JOIN banking.ledger_journals j ON j.id=e.aggregate_id JOIN banking.ledger_postings p ON p.journal_id=j.id
    JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id WHERE l.bank_account_id IS NOT NULL
)
SELECT 'JOURNAL_INVALID','ERROR','LEDGER_JOURNAL',t.id::text FROM journal_totals t
    WHERE t.status<>'POSTED' OR t.lines NOT BETWEEN 2 AND 100 OR t.accounts<2 OR t.delta<>0 OR NOT t.currency_ok
UNION ALL SELECT 'CUSTOMER_BALANCE_INVALID','ERROR','BANK_ACCOUNT',b.bank_account_id::text FROM balances b
    WHERE b.bank_account_id IS NOT NULL AND (b.balance<0 OR b.balance>99999999999999999999)
UNION ALL SELECT 'ACCOUNT_LEDGER_LINK_INVALID','ERROR','BANK_ACCOUNT',a.id::text FROM banking.accounts a
    WHERE a.ledger_provisioned IS DISTINCT FROM EXISTS(SELECT 1 FROM banking.ledger_accounts l WHERE l.bank_account_id=a.id)
UNION ALL SELECT 'HOLDS_EXCEED_POSTED','ERROR','BANK_ACCOUNT',b.bank_account_id::text FROM balances b
    WHERE b.bank_account_id IS NOT NULL AND b.balance<banking.reserved_balance_at(b.bank_account_id,p_snapshot)
UNION ALL SELECT 'OUTBOX_MISSING','ERROR','LEDGER_JOURNAL',j.id::text FROM banking.ledger_journals j
    WHERE j.status='POSTED' AND NOT EXISTS(SELECT 1 FROM banking.outbox_events e WHERE e.aggregate_id=j.id)
    AND NOT EXISTS(SELECT 1 FROM banking.reconciliation_journal_baseline b WHERE b.journal_id=j.id AND b.legacy_eventless)
UNION ALL SELECT 'OUTBOX_CONTENT_MISMATCH','ERROR','OUTBOX_EVENT',e.event_id::text
    FROM banking.outbox_events e LEFT JOIN banking.ledger_journals j ON j.id=e.aggregate_id
    WHERE j.status IS DISTINCT FROM 'POSTED' OR e.occurred_at IS DISTINCT FROM j.posted_at
    OR e.aggregate_type<>'LedgerJournal' OR e.aggregate_version<>1 OR e.schema_version<>1 OR e.event_type<>'LedgerJournalPosted'
    OR e.payload IS DISTINCT FROM jsonb_build_object('journalId',j.id,'referenceId',j.reference_id,'kind',j.kind,'currency',j.currency,
        'postings',(SELECT jsonb_agg(jsonb_build_object('ledgerAccountId',p.ledger_account_id,'side',p.side,'amount',trunc(p.amount)::text) ORDER BY p.id)
                    FROM banking.ledger_postings p WHERE p.journal_id=j.id))
UNION ALL SELECT 'OUTBOX_DELIVERY_MISSING','ERROR','OUTBOX_EVENT',e.event_id::text FROM banking.outbox_events e
    WHERE NOT EXISTS(SELECT 1 FROM banking.outbox_deliveries d WHERE d.event_id=e.event_id)
UNION ALL SELECT 'OUTBOX_DELIVERY_LAG','WARNING','OUTBOX_EVENT',e.event_id::text FROM banking.outbox_events e
    JOIN banking.outbox_deliveries d USING(event_id) WHERE d.state<>'DELIVERED' AND e.occurred_at<p_snapshot-make_interval(secs=>p_grace)
UNION ALL SELECT 'NOTIFICATION_PROCESSING_LAG','WARNING','OUTBOX_EVENT',e.event_id::text FROM banking.outbox_events e
    JOIN banking.outbox_deliveries d USING(event_id) WHERE d.state='DELIVERED' AND d.delivered_at<p_snapshot-make_interval(secs=>p_grace)
    AND NOT EXISTS(SELECT 1 FROM banking.notification_inbox i WHERE i.event_id=e.event_id)
UNION ALL SELECT 'NOTIFICATION_MISSING','ERROR','EVENT_ACCOUNT',x.event_id::text||':'||x.bank_account_id::text FROM expected_notifications x
    WHERE NOT EXISTS(SELECT 1 FROM banking.notifications n WHERE n.event_id=x.event_id AND n.account_id=x.bank_account_id)
UNION ALL SELECT 'NOTIFICATION_CONTENT_MISMATCH','ERROR','EVENT_ACCOUNT',n.event_id::text||':'||n.account_id::text FROM banking.notifications n
    WHERE NOT EXISTS(SELECT 1 FROM expected_notifications x WHERE x.event_id=n.event_id AND x.bank_account_id=n.account_id
        AND x.aggregate_id=n.journal_id AND x.occurred_at=n.occurred_at AND x.kind=n.kind)
UNION ALL SELECT 'NOTIFICATION_QUARANTINED','WARNING','KAFKA_RECORD',f.source_topic||':'||f.source_partition::text||':'||f.source_offset::text
    FROM banking.notification_failures f WHERE f.quarantined
UNION ALL SELECT 'NOTIFICATION_RETRY_LAG','WARNING','KAFKA_RECORD',f.source_topic||':'||f.source_partition::text||':'||f.source_offset::text
    FROM banking.notification_failures f WHERE NOT f.quarantined AND f.available_at<p_snapshot-make_interval(secs=>p_grace)
    AND NOT EXISTS(SELECT 1 FROM banking.notification_processed_sources s WHERE (s.source_topic,s.source_partition,s.source_offset)=(f.source_topic,f.source_partition,f.source_offset))
    AND NOT EXISTS(SELECT 1 FROM banking.reconciliation_retry_baseline b WHERE (b.source_topic,b.source_partition,b.source_offset)=(f.source_topic,f.source_partition,f.source_offset))
UNION ALL SELECT 'LEGACY_RETRY_STATUS_UNKNOWN','WARNING','KAFKA_RECORD',f.source_topic||':'||f.source_partition::text||':'||f.source_offset::text
    FROM banking.notification_failures f JOIN banking.reconciliation_retry_baseline b USING(source_topic,source_partition,source_offset)
    WHERE NOT f.quarantined AND NOT EXISTS(SELECT 1 FROM banking.notification_processed_sources s
        WHERE (s.source_topic,s.source_partition,s.source_offset)=(f.source_topic,f.source_partition,f.source_offset))
UNION ALL SELECT 'JOURNAL_AUDIT_MISSING','ERROR','LEDGER_JOURNAL',j.id::text FROM banking.ledger_journals j
    WHERE j.status='POSTED' AND NOT EXISTS(SELECT 1 FROM banking.reconciliation_journal_baseline b WHERE b.journal_id=j.id)
    AND NOT EXISTS(SELECT 1 FROM banking.audit_events a WHERE a.action='LEDGER_JOURNAL_POSTED' AND a.resource_id=j.id)
UNION ALL SELECT 'AUDIT_SOURCE_MISMATCH','ERROR','AUDIT_EVENT',a.id::text FROM banking.audit_events a
    WHERE (a.action='LEDGER_JOURNAL_POSTED' AND NOT EXISTS(SELECT 1 FROM banking.ledger_journals j WHERE j.id=a.resource_id
        AND a.resource_type='LEDGER_JOURNAL' AND a.actor_type='JOURNAL_ACTOR' AND j.status='POSTED'
        AND j.actor_issuer=a.actor_issuer AND j.actor_subject=a.actor_subject AND j.posted_at=a.occurred_at))
    OR (a.action='RECONCILIATION_COMPLETED' AND NOT EXISTS(SELECT 1 FROM banking.reconciliation_runs r WHERE r.id=a.resource_id
        AND a.resource_type='RECONCILIATION_RUN' AND r.actor_type=a.actor_type AND r.status<>'RUNNING'
        AND r.actor_issuer=a.actor_issuer AND r.actor_subject=a.actor_subject AND r.completed_at=a.occurred_at))
UNION ALL SELECT 'RECONCILIATION_AUDIT_MISSING','ERROR','RECONCILIATION_RUN',r.id::text FROM banking.reconciliation_runs r
    WHERE r.status<>'RUNNING' AND NOT EXISTS(SELECT 1 FROM banking.audit_events a WHERE a.action='RECONCILIATION_COMPLETED' AND a.resource_id=r.id)
$$;
