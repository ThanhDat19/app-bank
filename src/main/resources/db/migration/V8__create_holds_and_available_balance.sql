CREATE TABLE banking.holds (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    -- Delay FK KEY SHARE locks until after the application has locked the complete bank pair.
    account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    destination_account_id UUID NOT NULL REFERENCES banking.accounts(id) DEFERRABLE INITIALLY DEFERRED,
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount=trunc(amount) AND amount<=99999999999999999999),
    expires_at TIMESTAMPTZ NOT NULL CHECK(isfinite(expires_at)),
    state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','ACTIVE','CAPTURED','RELEASED','EXPIRED')),
    journal_id UUID UNIQUE REFERENCES banking.ledger_journals(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(actor_issuer,actor_subject,idempotency_key),
    CHECK(account_id<>destination_account_id),
    CHECK(state='CAPTURED' OR journal_id IS NULL)
);
CREATE INDEX ix_holds_active_account ON banking.holds(account_id,expires_at) WHERE state='ACTIVE';

CREATE FUNCTION banking.reserved_balance(p_account UUID) RETURNS NUMERIC
LANGUAGE sql VOLATILE SET search_path=pg_catalog,banking AS $$
    SELECT COALESCE(sum(amount),0) FROM banking.holds
    WHERE account_id=p_account AND state='ACTIVE' AND expires_at>clock_timestamp()
$$;

CREATE FUNCTION banking.hold_before_write() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE account_state TEXT; provisioned BOOLEAN; ledger_id UUID;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Hold writes require read committed' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PENDING' OR NEW.journal_id IS NOT NULL THEN
            RAISE EXCEPTION 'Hold must start pending' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW)-'state'-'journal_id') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'journal_id') OR
       NOT ((OLD.state='PENDING' AND NEW.state='ACTIVE') OR
            (OLD.state='ACTIVE' AND NEW.state IN ('RELEASED','EXPIRED','CAPTURED')) OR
            (OLD.state='CAPTURED' AND OLD.journal_id IS NULL AND NEW.state='CAPTURED' AND NEW.journal_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'Invalid hold mutation' USING ERRCODE='23514';
    END IF;
    -- A capture locks both bank accounts together in UUID order; never lock source before destination.
    IF NEW.state IN ('ACTIVE','CAPTURED') THEN
        PERFORM id FROM banking.accounts WHERE id IN (NEW.account_id,NEW.destination_account_id) ORDER BY id FOR UPDATE;
    END IF;
    SELECT status,ledger_provisioned INTO account_state,provisioned
        FROM banking.accounts WHERE id=NEW.account_id FOR UPDATE;
    IF NEW.state IN ('ACTIVE','CAPTURED') AND (account_state<>'ACTIVE' OR NOT provisioned OR NEW.expires_at<=clock_timestamp()) THEN
        RAISE EXCEPTION 'Hold requires active provisioned source and future expiry' USING ERRCODE='23514';
    END IF;
    IF NEW.state IN ('ACTIVE','CAPTURED') AND NOT EXISTS (
        SELECT 1 FROM banking.accounts WHERE id=NEW.destination_account_id AND status='ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Hold requires active destination' USING ERRCODE='23514';
    END IF;
    IF NEW.state='EXPIRED' AND NEW.expires_at>clock_timestamp() THEN
        RAISE EXCEPTION 'Hold has not expired' USING ERRCODE='23514';
    END IF;
    IF NEW.state='ACTIVE' THEN
        SELECT id INTO ledger_id FROM banking.ledger_accounts WHERE bank_account_id=NEW.account_id;
        IF ledger_id IS NULL OR banking.ledger_balance(ledger_id)-banking.reserved_balance(NEW.account_id)<NEW.amount THEN
            RAISE EXCEPTION 'Insufficient available balance for hold' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hold_validate BEFORE INSERT OR UPDATE ON banking.holds
FOR EACH ROW EXECUTE FUNCTION banking.hold_before_write();
CREATE TRIGGER hold_no_delete BEFORE DELETE ON banking.holds FOR EACH ROW EXECUTE FUNCTION banking.ledger_reject_mutation();
CREATE TRIGGER hold_no_truncate BEFORE TRUNCATE ON banking.holds EXECUTE FUNCTION banking.ledger_reject_mutation();

CREATE FUNCTION banking.hold_at_commit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE h banking.holds%ROWTYPE;
BEGIN
    SELECT * INTO STRICT h FROM banking.holds WHERE id=NEW.id;
    IF h.state='PENDING' OR NOT EXISTS (SELECT 1 FROM banking.accounts WHERE id=h.destination_account_id) OR NOT EXISTS (
        SELECT 1 FROM banking.accounts a JOIN banking.customers c ON c.id=a.customer_id
        WHERE a.id=h.account_id AND c.identity_issuer=h.actor_issuer AND c.identity_subject=h.actor_subject
    ) THEN
        RAISE EXCEPTION 'Hold must be finalized and owned' USING ERRCODE='23514';
    END IF;
    IF h.state='CAPTURED' AND (NOT EXISTS (
        SELECT 1 FROM banking.ledger_journals j WHERE j.id=h.journal_id AND j.reference_id=h.id
        AND j.status='POSTED' AND j.kind='INTERNAL_TRANSFER' AND j.currency='VND'
        AND j.actor_issuer=h.actor_issuer AND j.actor_subject=h.actor_subject
    ) OR (SELECT count(*) FROM banking.ledger_postings WHERE journal_id=h.journal_id)<>2
    OR NOT EXISTS (SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=h.journal_id AND a.bank_account_id=h.account_id AND p.side='DEBIT' AND p.amount=h.amount)
    OR NOT EXISTS (SELECT 1 FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id
        WHERE p.journal_id=h.journal_id AND a.bank_account_id=h.destination_account_id AND p.side='CREDIT' AND p.amount=h.amount)) THEN
        RAISE EXCEPTION 'Capture requires its exact full posted transfer' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER hold_commit AFTER INSERT OR UPDATE ON banking.holds
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.hold_at_commit();

-- Runs after the existing journal validator has acquired ordered bank/ledger locks.
-- All posting callers, including reversal and direct SQL, must preserve reservations.
CREATE FUNCTION banking.ledger_available_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
DECLARE a RECORD;
BEGIN
    IF NEW.status='POSTED' THEN
        FOR a IN SELECT DISTINCT l.id,l.bank_account_id FROM banking.ledger_accounts l
            JOIN banking.ledger_postings p ON p.ledger_account_id=l.id
            WHERE p.journal_id=NEW.id AND l.bank_account_id IS NOT NULL
        LOOP
            IF banking.ledger_balance(a.id)<banking.reserved_balance(a.bank_account_id) THEN
                RAISE EXCEPTION 'Posting would spend reserved money' USING ERRCODE='23514';
            END IF;
        END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER ledger_available_validate AFTER UPDATE ON banking.ledger_journals
FOR EACH ROW EXECUTE FUNCTION banking.ledger_available_guard();

CREATE FUNCTION banking.hold_closure_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,banking AS $$
BEGIN
    IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' AND banking.reserved_balance(NEW.id)<>0 THEN
        RAISE EXCEPTION 'Account has active reservations' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hold_account_close BEFORE UPDATE ON banking.accounts
FOR EACH ROW EXECUTE FUNCTION banking.hold_closure_guard();
