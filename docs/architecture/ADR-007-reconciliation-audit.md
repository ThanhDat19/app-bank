# ADR-007 — Snapshot reconciliation và actor/action/time audit

Status: proposed for owner review. BL-014 [issue #29](https://github.com/ThanhDat19/app-bank/issues/29).
Dependency: BL-013 PR #28 merged, base main `371aa0b`.

## Decision

Run reconciliation in one REPEATABLE READ transaction. The first advisory-lock query establishes the MVCC
snapshot; all source scans/hash checks use that snapshot while report/findings/audit commit atomically.
SnapshotAt is the database transaction timestamp, not an exported snapshot token or a reconstruction guarantee.
Sources are read only: the job writes reconciliation tables and its own audit event, never repairs money,
holds, outbox, notifications or Kafka offsets. Global transaction advisory lock (1414,1) prevents overlapping runs.
Runs have a 30-second transaction timeout; scalability/SLOs for production-sized data are not claimed.

Structural findings are ERROR. Delivery/consumer backlog after configurable grace is WARNING because the
pipeline is eventually consistent and can be deliberately disabled. CLEAN means no findings in this snapshot;
WARNING means warnings only; MISMATCH means at least one error. It does not mean external bank settlement,
Kafka replication health or a security/compliance certification.

Completed reports/findings are immutable. A failed persistence/audit operation rolls the run back completely;
RUNNING cannot survive commit. Recovered data requires a new run; old findings/status never change.

## Rules

| Code | Severity | Comparison |
| --- | --- | --- |
| JOURNAL_INVALID | ERROR | POSTED, 2–100 lines, distinct accounts, debit=credit and currency agreement |
| CUSTOMER_BALANCE_INVALID | ERROR | Independently sum POSTED entries by normal side; customer balance 0..20-digit max |
| ACCOUNT_LEDGER_LINK_INVALID | ERROR | Provisioning marker iff linked customer ledger account |
| HOLDS_EXCEED_POSTED | ERROR | Active, unexpired holds at snapshotAt do not exceed posted balance |
| OUTBOX_MISSING | ERROR | Non-exempt posted journal has an outbox envelope |
| OUTBOX_CONTENT_MISMATCH | ERROR | Journal/header/time/schema and independently rebuilt payload match |
| OUTBOX_DELIVERY_MISSING | ERROR | Envelope has delivery state |
| OUTBOX_DELIVERY_LAG | WARNING | Non-delivered journal event older than grace |
| NOTIFICATION_PROCESSING_LAG | WARNING | Delivered longer than grace, inbox still absent |
| NOTIFICATION_MISSING | ERROR | Processed inbox has every expected customer-account notification |
| NOTIFICATION_CONTENT_MISMATCH | ERROR | Existing projection matches event/journal/target/kind/time |
| INBOX_HASH_MISMATCH | ERROR | Inbox hash matches the canonical OutboxEvent serialization contract |
| NOTIFICATION_QUARANTINED | WARNING | Quarantined source-record inventory remains present |
| NOTIFICATION_RETRY_LAG | WARNING | Aged failed source with no successful source receipt, outside legacy baseline |
| LEGACY_RETRY_STATUS_UNKNOWN | WARNING | Pre-V12 retry metadata has no positive receipt proving its outcome |
| JOURNAL_AUDIT_MISSING | ERROR | New non-baseline posted journal has its audit |
| RECONCILIATION_AUDIT_MISSING | ERROR | Completed report has its audit |
| AUDIT_SOURCE_MISMATCH | ERROR | Audit actor/action/resource/time matches journal or completed run |

There is no cached balance projection in this project. Reconciliation checks derived ledger balance and the
actual notification projection; it does not invent a second authoritative balance.

## Cutover and provenance

V10 deliberately did not backfill legacy journal events. V12 freezes every existing journal ID and whether it
is currently eventless in reconciliation_journal_baseline. Only the eventless subset is exempt from OUTBOX_MISSING;
all existing IDs are exempt from JOURNAL_AUDIT_MISSING because central audit starts at V12. The inventory cannot
be expanded/edited later. This distinguishes new backdated journal inserts correctly without posted_at heuristics.
It cannot prove whether a missing event already present at V12 cutover was legacy or a pre-existing defect;
owner must review that inventory. No audit identity is inferred or fabricated for old records.

BL-013 retains failure rows after successful retry. To avoid falsely reporting recovered failures as backlog,
V12 adds immutable notification_processed_sources (topic/partition/offset/eventId). NotificationService writes a
receipt in the same transaction as inbox/notifications, including a deduplicated event at a new source offset.
Receipt failure rolls back processing, so consumer cannot commit that offset. Reconciliation ignores retry rows
with such positive receipts. Legacy non-quarantined failure coordinates are inventoried once and reported UNKNOWN
until a successful receipt exists; no success/failure outcome is guessed from a hash or Kafka timestamp.
Receipts prove DB processing, not broker offset commit. Old consumer instances must be stopped/upgraded for this cutover;
rolling mixed versions, broker/topic incarnation reuse and replay policy need separate planning.

## Audit

New POSTED journals emit immutable LEDGER_JOURNAL_POSTED audit inside the financial transaction. Actor issuer/subject
and time mirror the original journal fields; JOURNAL_ACTOR does not infer a role or authenticate a raw SQL writer.
Public financial endpoints retain their existing verified JWT actor contract. Completed reports emit
RECONCILIATION_COMPLETED with verified OPERATOR actor or explicit SYSTEM scheduler identity.

Audit keeps required actor identifiers, action, resource UUID/type and occurrence time. It does not copy JWTs,
profiles, reasons, request bodies or arbitrary exception text. Audit insert failure rolls back journal/event/money
or the report respectively. Only OPERATOR APIs expose this catalog. It is not WORM/tamper-proof storage:
a DB owner can disable guards or alter both sources and audit. Existing KYC/account histories remain in their
own modules; authentication/read access/full lifecycle audit are not centralized here.

## Limits

No repair, external statement comparison, Kafka committed-offset/cluster inspection, long-term cleanup or
broker replay. Quarantine is an inventory warning; replay to a new offset does not silently clear old quarantine.
Findings for a completed run support stable keyset pages. Audit browsing by insertion ID is best effort under
concurrent commits, not an exactly-once incremental export. Scheduler defaults off; reports and source receipts
have no automatic retention pruning.
