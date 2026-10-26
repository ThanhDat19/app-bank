CREATE SEQUENCE banking.account_number_sequence START WITH 100000000000 MAXVALUE 999999999999 NO CYCLE;

CREATE TABLE banking.accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES banking.customers(id),
    account_number VARCHAR(12) NOT NULL DEFAULT nextval('banking.account_number_sequence')::text
        CHECK (account_number ~ '^[0-9]{12}$'),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'VND'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ledger_provisioned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    CONSTRAINT uq_accounts_number UNIQUE (account_number),
    CONSTRAINT uq_accounts_customer_currency UNIQUE (customer_id, currency)
);

CREATE TABLE banking.account_state_changes (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES banking.accounts(id),
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    actor_issuer VARCHAR(512) NOT NULL CHECK (btrim(actor_issuer) <> ''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject) <> ''),
    account_version BIGINT NOT NULL CHECK (account_version >= 0),
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_account_state_version UNIQUE (account_id, account_version),
    CONSTRAINT ck_account_state_change CHECK (COALESCE(
      (from_status IS NULL AND to_status = 'ACTIVE' AND reason = 'OPENED' AND account_version = 0) OR
      (from_status = 'ACTIVE' AND to_status = 'FROZEN' AND reason IN ('CUSTOMER_REQUEST', 'SECURITY_REVIEW') AND account_version > 0) OR
      (from_status = 'FROZEN' AND to_status = 'ACTIVE' AND reason = 'REVIEW_CLEARED' AND account_version > 0) OR
      (from_status = 'ACTIVE' AND to_status = 'CLOSED' AND reason = 'CUSTOMER_REQUEST' AND account_version > 0), FALSE))
);

COMMENT ON COLUMN banking.accounts.ledger_provisioned IS
    'Future ledger provisioning MUST lock this account, require non-CLOSED, and set true atomically with ledger linkage. Never reset to false.';
COMMENT ON TABLE banking.accounts IS 'Account lifecycle metadata; no monetary balance is maintained here';
