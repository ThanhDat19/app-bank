# ADR-001: Ledger Consistency Boundary

- **Status**: proposed; needs Product Owner review.
- **Decision**: account financial state, money movement, idempotency claim, journal postings and outbox event intent are committed in one PostgreSQL ACID transaction for simulated internal transfer.
- **Rationale**: debit and credit in separate distributed DB transactions allow partial money movement.
- **Invariants**:
  1. Posted journal is append-only; reversals create new journals.
  2. Per journal and per currency, sum of signed postings equals zero.
  3. Source available funds must not be exceeded; posted balance and holds are separate concepts.
  4. Concurrent transfers lock account rows in deterministic order or use a proven equivalent concurrency protocol.
  5. Unique business reference and idempotency key protect ambiguous network retries.
  6. A committed financial command includes a committed outbox record; downstream Kafka is not the source of truth for the ledger.
- **Open review decisions**: chart of accounts, simulated cash-in counter-account, posting semantics (signed vs debit/credit), holds/reservations, money unit representation, bank/operator adjustment authorization, posting state machine, reconciliation policy.
