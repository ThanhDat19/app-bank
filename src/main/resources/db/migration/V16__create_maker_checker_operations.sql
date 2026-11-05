CREATE TABLE banking.operation_requests (
    id UUID PRIMARY KEY,
    maker_issuer VARCHAR(512) NOT NULL CHECK(btrim(maker_issuer)<>''),
    maker_subject VARCHAR(255) NOT NULL CHECK(btrim(maker_subject)<>''),
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash VARCHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    type TEXT NOT NULL CHECK(type IN ('FREEZE','UNFREEZE','REVERSAL')),
    target_id UUID NOT NULL,
    expected_account_version BIGINT,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','EXECUTING','APPROVED','REJECTED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version IN (0,1)),
    checker_issuer VARCHAR(512), checker_subject VARCHAR(255),
    execution_txid TEXT,
    result JSONB,
    decision_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    UNIQUE(maker_issuer,maker_subject,idempotency_key),
    CHECK((type='FREEZE' AND expected_account_version>=0 AND reason IN ('CUSTOMER_REQUEST','SECURITY_REVIEW'))
       OR (type='UNFREEZE' AND expected_account_version>=0 AND reason='REVIEW_CLEARED')
       OR (type='REVERSAL' AND expected_account_version IS NULL AND reason IN ('OPERATOR_ERROR','DUPLICATE_PROCESSING'))),
    CHECK(type='REVERSAL' OR expected_account_version IS NOT NULL),
    CHECK((checker_issuer IS NULL AND checker_subject IS NULL) OR
          (checker_issuer IS NOT NULL AND checker_subject IS NOT NULL AND btrim(checker_issuer)<>'' AND btrim(checker_subject)<>''
           AND (maker_issuer,maker_subject) IS DISTINCT FROM (checker_issuer,checker_subject))),
    CHECK(COALESCE((status='PENDING' AND version=0 AND checker_issuer IS NULL AND execution_txid IS NULL AND result IS NULL AND decision_reason IS NULL AND completed_at IS NULL)
       OR (status='EXECUTING' AND version=0 AND checker_issuer IS NOT NULL AND execution_txid IS NOT NULL AND result IS NULL AND decision_reason IS NULL AND completed_at IS NULL)
       OR (status='APPROVED' AND version=1 AND checker_issuer IS NOT NULL AND execution_txid IS NOT NULL AND result IS NOT NULL AND jsonb_typeof(result)='object' AND decision_reason='APPROVED' AND completed_at>=created_at)
       OR (status='REJECTED' AND version=1 AND checker_issuer IS NOT NULL AND execution_txid IS NULL AND result IS NULL AND decision_reason='NOT_JUSTIFIED' AND completed_at>=created_at)
       OR (status='CANCELLED' AND version=1 AND checker_issuer IS NULL AND execution_txid IS NULL AND result IS NULL AND decision_reason='MAKER_WITHDREW' AND completed_at>=created_at),FALSE))
);
CREATE INDEX ix_operation_queue ON banking.operation_requests(status,created_at DESC,id DESC);
CREATE UNIQUE INDEX uq_operation_execution_transaction ON banking.operation_requests(execution_txid) WHERE status='EXECUTING';
CREATE TABLE banking.operation_history (
    request_id UUID NOT NULL REFERENCES banking.operation_requests(id),
    version BIGINT NOT NULL CHECK(version IN (0,1)),
    status TEXT NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    reason TEXT NOT NULL,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(request_id,version)
);
ALTER TABLE banking.account_state_changes ADD COLUMN operation_request_id UUID UNIQUE REFERENCES banking.operation_requests(id);
ALTER TABLE banking.reversals ADD COLUMN operation_request_id UUID UNIQUE REFERENCES banking.operation_requests(id);
CREATE TRIGGER account_history_immutable BEFORE UPDATE OR DELETE ON banking.account_state_changes FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER account_history_no_truncate BEFORE TRUNCATE ON banking.account_state_changes EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.operation_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.type IN ('FREEZE','UNFREEZE') THEN
        IF NOT EXISTS(SELECT 1 FROM banking.accounts a JOIN banking.customers c ON c.id=a.customer_id WHERE a.id=NEW.target_id
          AND (c.identity_issuer,c.identity_subject) IS DISTINCT FROM (NEW.maker_issuer,NEW.maker_subject)
          AND (NEW.checker_issuer IS NULL OR (c.identity_issuer,c.identity_subject) IS DISTINCT FROM (NEW.checker_issuer,NEW.checker_subject))) THEN
            RAISE EXCEPTION 'Operators cannot control their own account' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'PENDING' THEN RAISE EXCEPTION 'Operation must start pending' USING ERRCODE='23514'; END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','version','checker_issuer','checker_subject','execution_txid','result','decision_reason','completed_at'])
           IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','checker_issuer','checker_subject','execution_txid','result','decision_reason','completed_at'])
           OR NOT ((OLD.status='PENDING' AND NEW.status IN ('EXECUTING','REJECTED','CANCELLED'))
               OR (OLD.status='EXECUTING' AND NEW.status='APPROVED'
                   AND (NEW.checker_issuer,NEW.checker_subject,NEW.execution_txid) IS NOT DISTINCT FROM
                       (OLD.checker_issuer,OLD.checker_subject,OLD.execution_txid))) THEN
            RAISE EXCEPTION 'Immutable intent and terminal decision' USING ERRCODE='23514';
        END IF;
        IF NEW.execution_txid IS NOT NULL AND NEW.execution_txid<>pg_current_xact_id()::text THEN
            RAISE EXCEPTION 'Execution is bound to its transaction' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER operation_validate BEFORE INSERT OR UPDATE ON banking.operation_requests FOR EACH ROW EXECUTE FUNCTION banking.operation_validate();
CREATE TRIGGER operation_no_delete BEFORE DELETE ON banking.operation_requests FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER operation_no_truncate BEFORE TRUNCATE ON banking.operation_requests EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.operation_history_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.operation_requests r WHERE r.id=NEW.request_id AND r.status=NEW.status AND r.version=NEW.version
      AND NEW.reason=COALESCE(r.decision_reason,r.reason)
      AND NEW.actor_issuer=CASE WHEN r.status IN ('PENDING','CANCELLED') THEN r.maker_issuer ELSE r.checker_issuer END
      AND NEW.actor_subject=CASE WHEN r.status IN ('PENDING','CANCELLED') THEN r.maker_subject ELSE r.checker_subject END
      AND NEW.occurred_at=COALESCE(r.completed_at,r.created_at)) THEN
        RAISE EXCEPTION 'History must match durable decision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER operation_history_check BEFORE INSERT ON banking.operation_history FOR EACH ROW EXECUTE FUNCTION banking.operation_history_validate();
CREATE TRIGGER operation_history_immutable BEFORE UPDATE OR DELETE ON banking.operation_history FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER operation_history_no_truncate BEFORE TRUNCATE ON banking.operation_history EXECUTE FUNCTION banking.ledger_reject_mutation();

ALTER TABLE banking.audit_events DROP CONSTRAINT audit_events_action_check;
ALTER TABLE banking.audit_events ADD CONSTRAINT audit_events_action_check CHECK(action IN
 ('LEDGER_JOURNAL_POSTED','RECONCILIATION_COMPLETED','OPERATION_REQUESTED','OPERATION_APPROVED','OPERATION_REJECTED','OPERATION_CANCELLED'));
ALTER TABLE banking.audit_events DROP CONSTRAINT audit_events_resource_type_check;
ALTER TABLE banking.audit_events ADD CONSTRAINT audit_events_resource_type_check CHECK(resource_type IN ('LEDGER_JOURNAL','RECONCILIATION_RUN','OPERATION_REQUEST'));
CREATE OR REPLACE FUNCTION banking.audit_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.action='LEDGER_JOURNAL_POSTED' THEN
        IF NEW.resource_type<>'LEDGER_JOURNAL' OR NEW.actor_type<>'JOURNAL_ACTOR' OR NOT EXISTS(
            SELECT 1 FROM banking.ledger_journals j WHERE j.id=NEW.resource_id AND j.status='POSTED'
            AND j.actor_issuer=NEW.actor_issuer AND j.actor_subject=NEW.actor_subject AND j.posted_at=NEW.occurred_at) THEN
            RAISE EXCEPTION 'Audit must match posted journal actor and time' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.action='RECONCILIATION_COMPLETED' THEN
        IF NEW.resource_type<>'RECONCILIATION_RUN' OR NOT EXISTS(
            SELECT 1 FROM banking.reconciliation_runs r WHERE r.id=NEW.resource_id AND r.status<>'RUNNING'
            AND r.actor_issuer=NEW.actor_issuer AND r.actor_subject=NEW.actor_subject
            AND r.actor_type=NEW.actor_type AND r.completed_at=NEW.occurred_at) THEN
            RAISE EXCEPTION 'Audit must match completed report' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.resource_type<>'OPERATION_REQUEST' OR NEW.actor_type<>'OPERATOR' OR NOT EXISTS(
            SELECT 1 FROM banking.operation_history h WHERE h.request_id=NEW.resource_id
            AND NEW.action=CASE WHEN h.status='PENDING' THEN 'OPERATION_REQUESTED' ELSE 'OPERATION_'||h.status END
            AND NEW.actor_issuer=h.actor_issuer AND NEW.actor_subject=h.actor_subject AND NEW.occurred_at=h.occurred_at) THEN
            RAISE EXCEPTION 'Audit must match operation history actor and time' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION banking.operation_audit_history() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    INSERT INTO banking.audit_events(actor_issuer,actor_subject,actor_type,action,resource_type,resource_id,occurred_at)
    VALUES(NEW.actor_issuer,NEW.actor_subject,'OPERATOR',
      CASE WHEN NEW.status='PENDING' THEN 'OPERATION_REQUESTED' ELSE 'OPERATION_'||NEW.status END,
      'OPERATION_REQUEST',NEW.request_id,NEW.occurred_at);
    RETURN NULL;
END $$;
CREATE TRIGGER operation_history_audit AFTER INSERT ON banking.operation_history FOR EACH ROW EXECUTE FUNCTION banking.operation_audit_history();
CREATE FUNCTION banking.operation_record_history() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.status<>'EXECUTING' THEN
        INSERT INTO banking.operation_history(request_id,version,status,reason,actor_issuer,actor_subject,occurred_at)
        VALUES(NEW.id,NEW.version,NEW.status,COALESCE(NEW.decision_reason,NEW.reason),
          CASE WHEN NEW.status IN ('PENDING','CANCELLED') THEN NEW.maker_issuer ELSE NEW.checker_issuer END,
          CASE WHEN NEW.status IN ('PENDING','CANCELLED') THEN NEW.maker_subject ELSE NEW.checker_subject END,
          COALESCE(NEW.completed_at,NEW.created_at));
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER operation_history_auto AFTER INSERT OR UPDATE ON banking.operation_requests FOR EACH ROW EXECUTE FUNCTION banking.operation_record_history();

CREATE FUNCTION banking.operation_account_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status AND (OLD.status='FROZEN' OR NEW.status='FROZEN') THEN
        IF NOT EXISTS(SELECT 1 FROM banking.operation_requests r WHERE r.status='EXECUTING' AND r.execution_txid=pg_current_xact_id()::text
          AND r.target_id=OLD.id AND r.expected_account_version=OLD.version AND NEW.version=OLD.version+1
          AND ((r.type='FREEZE' AND OLD.status='ACTIVE' AND NEW.status='FROZEN') OR (r.type='UNFREEZE' AND OLD.status='FROZEN' AND NEW.status='ACTIVE'))) THEN
            RAISE EXCEPTION 'Account operation requires two-person execution' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER operation_account_guard BEFORE UPDATE ON banking.accounts FOR EACH ROW EXECUTE FUNCTION banking.operation_account_guard();
CREATE FUNCTION banking.operation_link_account_history() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE r banking.operation_requests%ROWTYPE;
BEGIN
    IF NEW.from_status='FROZEN' OR NEW.to_status='FROZEN' THEN
        SELECT * INTO r FROM banking.operation_requests WHERE status='EXECUTING' AND execution_txid=pg_current_xact_id()::text
          AND target_id=NEW.account_id AND expected_account_version=NEW.account_version-1 AND reason=NEW.reason
          AND checker_issuer=NEW.actor_issuer AND checker_subject=NEW.actor_subject
          AND ((type='FREEZE' AND NEW.from_status='ACTIVE' AND NEW.to_status='FROZEN') OR (type='UNFREEZE' AND NEW.from_status='FROZEN' AND NEW.to_status='ACTIVE'));
        IF NOT FOUND OR (NEW.operation_request_id IS NOT NULL AND NEW.operation_request_id<>r.id) THEN
            RAISE EXCEPTION 'Account history requires matching execution' USING ERRCODE='23514';
        END IF;
        NEW.operation_request_id=r.id;
    ELSIF NEW.operation_request_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected operation link' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER operation_account_history BEFORE INSERT ON banking.account_state_changes FOR EACH ROW EXECUTE FUNCTION banking.operation_link_account_history();
CREATE FUNCTION banking.operation_link_reversal() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE r banking.operation_requests%ROWTYPE;
BEGIN
    SELECT * INTO r FROM banking.operation_requests WHERE status='EXECUTING' AND execution_txid=pg_current_xact_id()::text
      AND type='REVERSAL' AND target_id=NEW.original_journal_id AND reason=NEW.reason AND id::text=NEW.idempotency_key
      AND checker_issuer=NEW.actor_issuer AND checker_subject=NEW.actor_subject;
    IF NOT FOUND OR (NEW.operation_request_id IS NOT NULL AND NEW.operation_request_id<>r.id) THEN
        RAISE EXCEPTION 'Reversal requires matching two-person execution' USING ERRCODE='23514';
    END IF;
    NEW.operation_request_id=r.id;
    RETURN NEW;
END $$;
CREATE TRIGGER operation_reversal_link BEFORE INSERT ON banking.reversals FOR EACH ROW EXECUTE FUNCTION banking.operation_link_reversal();

CREATE FUNCTION banking.operation_require_complete() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE r banking.operation_requests%ROWTYPE;
BEGIN
    SELECT * INTO STRICT r FROM banking.operation_requests WHERE id=NEW.id;
    IF r.status='EXECUTING' OR NOT EXISTS(SELECT 1 FROM banking.operation_history WHERE request_id=r.id AND version=r.version AND status=r.status) THEN
        RAISE EXCEPTION 'Operation cannot commit without complete decision history' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM banking.operation_history h WHERE h.request_id=r.id AND NOT EXISTS(
      SELECT 1 FROM banking.audit_events a WHERE a.resource_type='OPERATION_REQUEST' AND a.resource_id=r.id
      AND a.action=CASE WHEN h.status='PENDING' THEN 'OPERATION_REQUESTED' ELSE 'OPERATION_'||h.status END
      AND a.actor_type='OPERATOR' AND a.actor_issuer=h.actor_issuer AND a.actor_subject=h.actor_subject AND a.occurred_at=h.occurred_at)) THEN
        RAISE EXCEPTION 'Operation history requires matching central audit' USING ERRCODE='23514';
    END IF;
    IF r.status='APPROVED' THEN
        IF r.type='REVERSAL' THEN
            IF NOT EXISTS(SELECT 1 FROM banking.reversals v WHERE v.operation_request_id=r.id AND v.original_journal_id=r.target_id
              AND v.actor_issuer=r.checker_issuer AND v.actor_subject=r.checker_subject AND v.reason=r.reason AND v.journal_id IS NOT NULL
              AND r.result=jsonb_build_object('id',v.id,'originalJournalId',v.original_journal_id,'journalId',v.journal_id,
                'originalKind',v.original_kind,'amount',v.amount::text,'currency','VND','reason',v.reason,'status','POSTED','postedAt',r.result->'postedAt')
              AND (r.result->>'postedAt')::timestamptz=v.posted_at) THEN
                RAISE EXCEPTION 'Approval requires matching full reversal receipt' USING ERRCODE='23514';
            END IF;
        ELSE
            IF NOT EXISTS(SELECT 1 FROM banking.account_state_changes h JOIN banking.accounts a ON a.id=h.account_id
              WHERE h.operation_request_id=r.id AND h.account_id=r.target_id AND h.account_version=r.expected_account_version+1
              AND h.reason=r.reason AND h.actor_issuer=r.checker_issuer AND h.actor_subject=r.checker_subject
              AND a.version=h.account_version AND a.status=h.to_status AND a.updated_at=h.changed_at
              AND r.result=jsonb_build_object('id',a.id,'accountNumber',a.account_number,'currency',a.currency,'status',h.to_status,
                'version',h.account_version,'createdAt',r.result->'createdAt','updatedAt',r.result->'updatedAt')
              AND (r.result->>'createdAt')::timestamptz=a.created_at AND (r.result->>'updatedAt')::timestamptz=h.changed_at) THEN
                RAISE EXCEPTION 'Approval requires matching account history receipt' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER operation_complete AFTER INSERT OR UPDATE ON banking.operation_requests
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.operation_require_complete();

COMMENT ON TABLE banking.operation_history IS 'Append-only maker/checker audit. Verified identity and decisions are stored locally; public history redacts raw actor identity.';
