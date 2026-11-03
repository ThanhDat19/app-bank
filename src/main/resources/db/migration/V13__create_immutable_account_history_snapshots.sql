CREATE TABLE banking.account_history_snapshots(
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    ledger_account_id UUID NOT NULL REFERENCES banking.ledger_accounts(id) DEFERRABLE INITIALLY DEFERRED,
    mode TEXT NOT NULL CHECK(mode IN ('HISTORY','STATEMENT')),
    currency VARCHAR(3) NOT NULL CHECK(currency='VND'),
    timezone VARCHAR(100) NOT NULL CHECK(btrim(timezone)<>''),
    from_date DATE,
    to_date DATE,
    from_at TIMESTAMPTZ,
    requested_to_at TIMESTAMPTZ,
    effective_to_at TIMESTAMPTZ,
    snapshot_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('BUILDING','READY')),
    opening_balance NUMERIC NOT NULL CHECK(opening_balance=trunc(opening_balance)),
    credit_total NUMERIC NOT NULL DEFAULT 0 CHECK(credit_total>=0 AND credit_total=trunc(credit_total)),
    debit_total NUMERIC NOT NULL DEFAULT 0 CHECK(debit_total>=0 AND debit_total=trunc(debit_total)),
    closing_balance NUMERIC NOT NULL CHECK(closing_balance=trunc(closing_balance)),
    entry_count BIGINT NOT NULL DEFAULT 0 CHECK(entry_count>=0),
    completed_at TIMESTAMPTZ,
    CHECK((mode='HISTORY' AND timezone='UTC' AND from_date IS NULL AND to_date IS NULL AND from_at IS NULL AND requested_to_at IS NULL AND effective_to_at IS NULL)
       OR (mode='STATEMENT' AND from_date IS NOT NULL AND to_date IS NOT NULL AND to_date>from_date AND to_date-from_date<=366
           AND from_at IS NOT NULL AND requested_to_at IS NOT NULL AND effective_to_at IS NOT NULL AND from_at<snapshot_at
           AND requested_to_at>=from_at AND effective_to_at=least(requested_to_at,snapshot_at) AND effective_to_at>=from_at)),
    CHECK((status='BUILDING' AND entry_count=0 AND credit_total=0 AND debit_total=0 AND closing_balance=opening_balance AND completed_at IS NULL)
       OR (status='READY' AND completed_at IS NOT NULL AND completed_at>=snapshot_at))
);
CREATE INDEX ix_history_snapshots_account ON banking.account_history_snapshots(account_id,snapshot_at DESC);
CREATE TABLE banking.account_history_entries(
    snapshot_id UUID NOT NULL REFERENCES banking.account_history_snapshots(id),
    sequence BIGINT NOT NULL CHECK(sequence>0),
    posting_id UUID NOT NULL REFERENCES banking.ledger_postings(id),
    journal_id UUID NOT NULL REFERENCES banking.ledger_journals(id),
    reference_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    side VARCHAR(6) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    delta NUMERIC NOT NULL CHECK(delta=trunc(delta)),
    running_balance NUMERIC NOT NULL CHECK(running_balance=trunc(running_balance)),
    PRIMARY KEY(snapshot_id,sequence),
    UNIQUE(snapshot_id,posting_id)
);
CREATE FUNCTION banking.history_entry_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM banking.account_history_snapshots s
        JOIN banking.ledger_postings p ON p.id=NEW.posting_id AND p.ledger_account_id=s.ledger_account_id
        JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id JOIN banking.ledger_journals j ON j.id=p.journal_id
        WHERE s.id=NEW.snapshot_id AND s.status='BUILDING' AND j.status='POSTED'
        AND j.id=NEW.journal_id AND j.reference_id=NEW.reference_id AND j.kind=NEW.kind AND j.posted_at=NEW.posted_at
        AND p.side=NEW.side AND p.amount=NEW.amount AND NEW.delta=CASE WHEN p.side=l.normal_side THEN p.amount ELSE -p.amount END
        AND (s.mode='HISTORY' OR (j.posted_at>=s.from_at AND j.posted_at<s.effective_to_at))) THEN
        RAISE EXCEPTION 'History entry must match an eligible posted source' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER history_entry_check BEFORE INSERT ON banking.account_history_entries FOR EACH ROW EXECUTE FUNCTION banking.history_entry_validate();
CREATE TRIGGER history_entry_immutable BEFORE UPDATE OR DELETE ON banking.account_history_entries FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER history_entry_no_truncate BEFORE TRUNCATE ON banking.account_history_entries EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.history_snapshot_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE source_count BIGINT; copied_count BIGINT; first_sequence BIGINT; last_sequence BIGINT;
    credits NUMERIC; debits NUMERIC; opening NUMERIC;
BEGIN
    IF current_setting('transaction_isolation')<>'repeatable read' THEN
        RAISE EXCEPTION 'History materialization requires repeatable read' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'BUILDING' OR NEW.snapshot_at<>transaction_timestamp() OR NOT EXISTS(
            SELECT 1 FROM banking.ledger_accounts l WHERE l.id=NEW.ledger_account_id AND l.bank_account_id=NEW.account_id
            AND l.currency='VND' AND l.account_type='LIABILITY' AND l.normal_side='CREDIT') THEN
            RAISE EXCEPTION 'Snapshot requires a building customer ledger binding' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status<>'BUILDING' OR NEW.status<>'READY' OR
        (to_jsonb(NEW)-'status'-'credit_total'-'debit_total'-'closing_balance'-'entry_count'-'completed_at') IS DISTINCT FROM
        (to_jsonb(OLD)-'status'-'credit_total'-'debit_total'-'closing_balance'-'entry_count'-'completed_at') THEN
        RAISE EXCEPTION 'Only snapshot finalization is allowed' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO source_count FROM banking.ledger_postings p JOIN banking.ledger_journals j ON j.id=p.journal_id
        WHERE p.ledger_account_id=NEW.ledger_account_id AND j.status='POSTED'
        AND (NEW.mode='HISTORY' OR (j.posted_at>=NEW.from_at AND j.posted_at<NEW.effective_to_at));
    SELECT count(*),COALESCE(min(sequence),1),COALESCE(max(sequence),0),
        COALESCE(sum(amount) FILTER(WHERE side='CREDIT'),0),COALESCE(sum(amount) FILTER(WHERE side='DEBIT'),0)
        INTO copied_count,first_sequence,last_sequence,credits,debits FROM banking.account_history_entries WHERE snapshot_id=NEW.id;
    SELECT COALESCE(sum(CASE WHEN p.side='CREDIT' THEN p.amount ELSE -p.amount END),0) INTO opening
        FROM banking.ledger_postings p JOIN banking.ledger_journals j ON j.id=p.journal_id
        WHERE p.ledger_account_id=NEW.ledger_account_id AND j.status='POSTED' AND NEW.mode='STATEMENT' AND j.posted_at<NEW.from_at;
    IF source_count<>copied_count OR NEW.entry_count<>copied_count OR first_sequence<>1 OR last_sequence<>copied_count
        OR NEW.opening_balance<>opening OR NEW.credit_total<>credits OR NEW.debit_total<>debits OR NEW.closing_balance<>opening+credits-debits THEN
        RAISE EXCEPTION 'Snapshot must be complete and totals exact' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM (
        SELECT sequence,row_number() OVER w expected_sequence,running_balance,NEW.opening_balance+sum(delta) OVER w expected_balance
        FROM banking.account_history_entries WHERE snapshot_id=NEW.id
        WINDOW w AS(ORDER BY posted_at,journal_id,posting_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
    ) t WHERE t.sequence<>t.expected_sequence OR t.running_balance<>t.expected_balance) THEN
        RAISE EXCEPTION 'Snapshot order and running balances must match' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER history_snapshot_check BEFORE INSERT OR UPDATE ON banking.account_history_snapshots FOR EACH ROW EXECUTE FUNCTION banking.history_snapshot_validate();
CREATE TRIGGER history_snapshot_no_delete BEFORE DELETE ON banking.account_history_snapshots FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER history_snapshot_no_truncate BEFORE TRUNCATE ON banking.account_history_snapshots EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE FUNCTION banking.history_require_ready() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM banking.account_history_snapshots WHERE id=NEW.id AND status<>'READY') THEN
        RAISE EXCEPTION 'Building snapshot cannot survive commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER history_ready_at_commit AFTER INSERT OR UPDATE ON banking.account_history_snapshots
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.history_require_ready();
