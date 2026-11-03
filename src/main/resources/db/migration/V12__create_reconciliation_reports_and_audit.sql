CREATE TABLE banking.notification_processed_sources(
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK(source_partition>=0),
    source_offset BIGINT NOT NULL CHECK(source_offset>=0),
    event_id UUID NOT NULL REFERENCES banking.notification_inbox(event_id),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(source_topic,source_partition,source_offset)
);
CREATE TRIGGER notification_source_immutable BEFORE UPDATE OR DELETE ON banking.notification_processed_sources
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER notification_source_no_truncate BEFORE TRUNCATE ON banking.notification_processed_sources EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TABLE banking.reconciliation_retry_baseline(
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    PRIMARY KEY(source_topic,source_partition,source_offset)
);
INSERT INTO banking.reconciliation_retry_baseline
SELECT source_topic,source_partition,source_offset FROM banking.notification_failures WHERE NOT quarantined;
CREATE TRIGGER reconciliation_retry_baseline_immutable BEFORE INSERT OR UPDATE OR DELETE ON banking.reconciliation_retry_baseline
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER reconciliation_retry_baseline_no_truncate BEFORE TRUNCATE ON banking.reconciliation_retry_baseline EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE TABLE banking.reconciliation_journal_baseline(
    journal_id UUID PRIMARY KEY REFERENCES banking.ledger_journals(id),
    legacy_eventless BOOLEAN NOT NULL
);
-- V10 did not backfill events. Freeze the cutover inventory; never infer legacy eligibility from posted_at.
INSERT INTO banking.reconciliation_journal_baseline
SELECT j.id,NOT EXISTS(SELECT 1 FROM banking.outbox_events e WHERE e.aggregate_id=j.id)
FROM banking.ledger_journals j;
CREATE TRIGGER reconciliation_baseline_immutable BEFORE INSERT OR UPDATE OR DELETE ON banking.reconciliation_journal_baseline
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER reconciliation_baseline_no_truncate BEFORE TRUNCATE ON banking.reconciliation_journal_baseline EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE TABLE banking.reconciliation_runs(
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL CHECK(btrim(actor_issuer)<>''),
    actor_subject VARCHAR(255) NOT NULL CHECK(btrim(actor_subject)<>''),
    actor_type TEXT NOT NULL CHECK(actor_type IN ('OPERATOR','SYSTEM')),
    snapshot_at TIMESTAMPTZ NOT NULL,
    grace_seconds INTEGER NOT NULL CHECK(grace_seconds BETWEEN 1 AND 86400),
    status TEXT NOT NULL CHECK(status IN ('RUNNING','CLEAN','WARNING','MISMATCH')),
    error_count BIGINT NOT NULL DEFAULT 0 CHECK(error_count>=0),
    warning_count BIGINT NOT NULL DEFAULT 0 CHECK(warning_count>=0),
    completed_at TIMESTAMPTZ,
    CHECK((status='RUNNING' AND completed_at IS NULL AND error_count=0 AND warning_count=0)
       OR (status<>'RUNNING' AND completed_at>=snapshot_at))
);
CREATE TABLE banking.reconciliation_findings(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES banking.reconciliation_runs(id),
    code VARCHAR(64) NOT NULL CHECK(code ~ '^[A-Z][A-Z0-9_]+$'),
    severity TEXT NOT NULL CHECK(severity IN ('ERROR','WARNING')),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(512) NOT NULL,
    UNIQUE(run_id,code,resource_type,resource_id)
);
CREATE INDEX ix_reconciliation_run_findings ON banking.reconciliation_findings(run_id,id);
CREATE TABLE banking.audit_events(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    actor_type TEXT NOT NULL CHECK(actor_type IN ('JOURNAL_ACTOR','OPERATOR','SYSTEM')),
    action TEXT NOT NULL CHECK(action IN ('LEDGER_JOURNAL_POSTED','RECONCILIATION_COMPLETED')),
    resource_type TEXT NOT NULL CHECK(resource_type IN ('LEDGER_JOURNAL','RECONCILIATION_RUN')),
    resource_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE(action,resource_id)
);

CREATE FUNCTION banking.reconciliation_finding_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.reconciliation_runs WHERE id=NEW.run_id AND status='RUNNING') THEN
        RAISE EXCEPTION 'Findings require a running report' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_finding_check BEFORE INSERT ON banking.reconciliation_findings
FOR EACH ROW EXECUTE FUNCTION banking.reconciliation_finding_validate();
CREATE TRIGGER reconciliation_finding_immutable BEFORE UPDATE OR DELETE ON banking.reconciliation_findings
FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER reconciliation_finding_no_truncate BEFORE TRUNCATE ON banking.reconciliation_findings EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.reconciliation_run_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE errors BIGINT; warnings BIGINT;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'RUNNING' THEN RAISE EXCEPTION 'Report must start running' USING ERRCODE='23514'; END IF;
    ELSE
        IF OLD.status<>'RUNNING' OR NEW.status='RUNNING' OR
            (to_jsonb(NEW)-'status'-'error_count'-'warning_count'-'completed_at') IS DISTINCT FROM
            (to_jsonb(OLD)-'status'-'error_count'-'warning_count'-'completed_at') THEN
            RAISE EXCEPTION 'Only report finalization is allowed' USING ERRCODE='23514';
        END IF;
        SELECT count(*) FILTER(WHERE severity='ERROR'),count(*) FILTER(WHERE severity='WARNING') INTO errors,warnings
            FROM banking.reconciliation_findings WHERE run_id=NEW.id;
        IF NEW.error_count<>errors OR NEW.warning_count<>warnings OR NEW.status<>
            (CASE WHEN errors>0 THEN 'MISMATCH' WHEN warnings>0 THEN 'WARNING' ELSE 'CLEAN' END) THEN
            RAISE EXCEPTION 'Report summary must match findings' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_run_check BEFORE INSERT OR UPDATE ON banking.reconciliation_runs
FOR EACH ROW EXECUTE FUNCTION banking.reconciliation_run_validate();
CREATE TRIGGER reconciliation_run_no_delete BEFORE DELETE ON banking.reconciliation_runs FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER reconciliation_run_no_truncate BEFORE TRUNCATE ON banking.reconciliation_runs EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE FUNCTION banking.reconciliation_require_complete() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM banking.reconciliation_runs WHERE id=NEW.id AND status='RUNNING') THEN
        RAISE EXCEPTION 'Running report cannot survive commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reconciliation_complete_at_commit AFTER INSERT OR UPDATE ON banking.reconciliation_runs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.reconciliation_require_complete();

CREATE FUNCTION banking.audit_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.action='LEDGER_JOURNAL_POSTED' THEN
        IF NEW.resource_type<>'LEDGER_JOURNAL' OR NEW.actor_type<>'JOURNAL_ACTOR' OR NOT EXISTS(
            SELECT 1 FROM banking.ledger_journals j WHERE j.id=NEW.resource_id AND j.status='POSTED'
            AND j.actor_issuer=NEW.actor_issuer AND j.actor_subject=NEW.actor_subject AND j.posted_at=NEW.occurred_at) THEN
            RAISE EXCEPTION 'Audit must match posted journal actor and time' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.resource_type<>'RECONCILIATION_RUN' OR NOT EXISTS(
            SELECT 1 FROM banking.reconciliation_runs r WHERE r.id=NEW.resource_id AND r.status<>'RUNNING'
            AND r.actor_issuer=NEW.actor_issuer AND r.actor_subject=NEW.actor_subject
            AND r.actor_type=NEW.actor_type AND r.completed_at=NEW.occurred_at) THEN
            RAISE EXCEPTION 'Audit must match completed report' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER audit_check BEFORE INSERT ON banking.audit_events FOR EACH ROW EXECUTE FUNCTION banking.audit_validate();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON banking.audit_events FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON banking.audit_events EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE FUNCTION banking.audit_posted_journal() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    INSERT INTO banking.audit_events(actor_issuer,actor_subject,actor_type,action,resource_type,resource_id,occurred_at)
    VALUES(NEW.actor_issuer,NEW.actor_subject,'JOURNAL_ACTOR','LEDGER_JOURNAL_POSTED','LEDGER_JOURNAL',NEW.id,NEW.posted_at);
    RETURN NULL;
END $$;
CREATE TRIGGER journal_audit AFTER UPDATE ON banking.ledger_journals
FOR EACH ROW WHEN(OLD.status='DRAFT' AND NEW.status='POSTED') EXECUTE FUNCTION banking.audit_posted_journal();
CREATE FUNCTION banking.audit_completed_reconciliation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    INSERT INTO banking.audit_events(actor_issuer,actor_subject,actor_type,action,resource_type,resource_id,occurred_at)
    VALUES(NEW.actor_issuer,NEW.actor_subject,NEW.actor_type,'RECONCILIATION_COMPLETED','RECONCILIATION_RUN',NEW.id,NEW.completed_at);
    RETURN NULL;
END $$;
CREATE TRIGGER reconciliation_audit AFTER UPDATE ON banking.reconciliation_runs
FOR EACH ROW WHEN(OLD.status='RUNNING' AND NEW.status<>'RUNNING') EXECUTE FUNCTION banking.audit_completed_reconciliation();

CREATE FUNCTION banking.reconciliation_scan(p_snapshot TIMESTAMPTZ,p_grace INTEGER)
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
    WHERE b.bank_account_id IS NOT NULL AND b.balance<(SELECT COALESCE(sum(h.amount),0) FROM banking.holds h
        WHERE h.account_id=b.bank_account_id AND h.state='ACTIVE' AND h.expires_at>p_snapshot)
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
