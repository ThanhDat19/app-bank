# ADR-005 — Transactional journal outbox

Status: proposed for BL-012 owner review. Dependency: BL-011 merged in PR #24, base `aa58fd8`.

## Decision

PostgreSQL V10 enqueues one immutable `LedgerJournalPosted` envelope when a new journal finalizes.
The event and initial delivery row share the financial transaction, including deferred receipt constraints.
This covers application posting and trusted SQL posting without introducing Java module dependency cycles.
Existing V1–V9 journals are preserved and not backfilled.

Aggregate is the immutable journal, not a bank account or transfer. Its single posted event has
`aggregateVersion=1`; unique aggregate_id prevents duplicate events for that journal. `schemaVersion=1`
versions the envelope payload independently. There is no global or per-account ordering guarantee.
Concurrent worker delivery can reorder different journals, including principal and fee journals.

Payload contains journal/reference UUIDs, kind, currency and postings with ledger UUID/side/amount-string.
Actor issuer/subject, JWT, customer profile, account number, KYC, reasons and current balances are excluded.
Amounts remain exact integer VND strings. Posting order is deterministic by posting UUID.

## Delivery

Separate mutable delivery state holds lease token, expiry, attempt count, retry availability and delivered time.
Workers claim bounded batches via FOR UPDATE SKIP LOCKED in short REQUIRES_NEW transactions.
No bank/ledger locks or database transaction are held during transport I/O. Ack/retry require the current,
unexpired token. A worker cannot acknowledge or requeue a claim replaced after lease expiry.

At-least-once is deliberate. If transport accepts and the worker crashes or ack fails, the lease expires and
the same immutable eventId may publish again. Consumer eventId dedupe is mandatory in BL-013.
Relay does not infer failure from a network timeout: transport may already have accepted the event.
Retry delay is 1s, 2s, 4s, …, capped at 300s; expired crashed leases are eligible immediately.
There is no attempt cutoff/DLQ here, so transient outages do not discard events.

Relay scheduling defaults off. Enabling requires an OutboxPublisher bean whose publish returns only after
durable transport acceptance. There is no no-op/logger publisher; absent adapter fails startup when enabled.
Kafka, notifications, dedupe stores, DLQ policy and broker integration belong to BL-013.

## Consequences

Broker outage does not roll back committed money. Outbox insert failure does roll back money, and financial
receipt failure rolls back the event. A delivered row is terminal; event identity/payload are immutable.
Publisher timeout and batch duration must fit lease duration to reduce overlapping publications; lease expiry
still permits duplicate transport attempts. Long-running publishers cannot be interrupted by fencing alone.

Historical journals lack outbox events. Holds place/release/expiry lack journals and therefore produce no
journal event; hold capture emits principal plus a separate fee event when applicable. Delivery rows/events
have no automatic retention cleanup; backlog/storage metrics and operational pruning need later policy.
DB-owner permissions can bypass triggers; production least privilege and tamper evidence remain separate work.
