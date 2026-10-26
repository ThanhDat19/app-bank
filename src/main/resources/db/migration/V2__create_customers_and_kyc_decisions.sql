CREATE TABLE banking.customers (
    id UUID PRIMARY KEY,
    identity_issuer VARCHAR(512) NOT NULL CHECK (btrim(identity_issuer) <> ''),
    identity_subject VARCHAR(255) NOT NULL CHECK (btrim(identity_subject) <> ''),
    display_name VARCHAR(80) NOT NULL CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 80),
    kyc_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    CONSTRAINT uq_customers_identity UNIQUE (identity_issuer, identity_subject)
);

CREATE TABLE banking.kyc_decisions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES banking.customers(id),
    from_status VARCHAR(16) NOT NULL CHECK (from_status = 'PENDING'),
    to_status VARCHAR(16) NOT NULL CHECK (to_status IN ('VERIFIED', 'REJECTED')),
    reason VARCHAR(32) NOT NULL,
    reviewer_issuer VARCHAR(512) NOT NULL CHECK (btrim(reviewer_issuer) <> ''),
    reviewer_subject VARCHAR(255) NOT NULL CHECK (btrim(reviewer_subject) <> ''),
    customer_version BIGINT NOT NULL CHECK (customer_version > 0),
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_kyc_decision_reason CHECK (
        (to_status = 'VERIFIED' AND reason = 'DOCUMENTS_MATCH') OR
        (to_status = 'REJECTED' AND reason IN ('INCOMPLETE', 'INCONSISTENT'))),
    CONSTRAINT uq_kyc_decision_customer UNIQUE (customer_id),
    CONSTRAINT uq_kyc_decision_version UNIQUE (customer_id, customer_version)
);

COMMENT ON TABLE banking.customers IS 'Synthetic customer profiles; not proof of real identity';
COMMENT ON TABLE banking.kyc_decisions IS 'Simulated manual KYC decision history, not a real eKYC result';
