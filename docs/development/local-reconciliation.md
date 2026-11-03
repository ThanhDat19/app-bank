# BL-014 — Run, diagnose and review reconciliation

Issue [#29](https://github.com/ThanhDat19/app-bank/issues/29), branch `feat/BL-014-reconciliation-audit`.
BL-013 PR #28 merged, branch based on updated main `371aa0b`.

## Use

When deliberately running the app locally, Flyway applies V12. Authenticate as an OPERATOR in Swagger,
POST the reconciliation run route without a body, then follow Location to the report and findings pages.
CLEAN/WARNING/MISMATCH are business results returned with 201, not transport failures. Review ERROR first;
WARNING can mean delivery/consumer deliberately disabled, age beyond grace, quarantine inventory or unknown legacy retry status.

The optional scheduler is disabled by default:

| Setting | Default / bounds |
| --- | --- |
| bank.reconciliation.enabled / RECONCILIATION_ENABLED | false |
| bank.reconciliation.grace-seconds / RECONCILIATION_GRACE_SECONDS | 300, range 1–86400 |
| bank.reconciliation.poll-millis | 3600000, positive Spring fixed delay |

Enabled scheduler uses explicit SYSTEM identity `urn:bank-lab:system` / `reconciliation-scheduler`.
Global advisory transaction lock rejects overlap with manual requests. Scan uses REPEATABLE READ and a
30-second transaction timeout. No Kafka broker connection or source data mutation is made by the scan.

Read-only database diagnosis:

```sql
SELECT id,snapshot_at,status,error_count,warning_count,completed_at
FROM banking.reconciliation_runs ORDER BY snapshot_at DESC LIMIT 20;
SELECT code,severity,count(*) FROM banking.reconciliation_findings
WHERE run_id='<report UUID>' GROUP BY code,severity ORDER BY code;
SELECT id,action,resource_type,resource_id,occurred_at
FROM banking.audit_events ORDER BY id DESC LIMIT 100;
SELECT journal_id FROM banking.reconciliation_journal_baseline WHERE legacy_eventless;
```

Review cutover inventories before interpreting historical coverage. Eventless journals existing at V12 are
exempt from missing-event checks; pre-V12 journal audits are not backfilled. Pre-V12 retry rows without positive
source receipts are UNKNOWN, not assumed still pending or already successful. New successful consumption records
topic/partition/offset/eventId atomically with inbox; prior failure history can remain without a false retry-lag warning.

Investigate actual ledger/receipt/source state before proposing a repair. Restore broker/consumer for pipeline
lag, then create a new report. No endpoint applies a repair, removes a finding, clears quarantine or replays a record.
An old report remains immutable even after recovery. Long-term retention, external settlement statements,
Kafka offset inspection, full auth/lifecycle audit and tamper-proof audit storage are outside this task.

## Test boundary

ReconciliationIT uses PostgreSQL Testcontainers and real RSA/JWKS HTTP. Controlled corruption/fault injection
disables guards only inside disposable fixtures, restores them and never touches bank_lab. Snapshot test pauses
report insertion with a separate advisory lock, commits a new source gap concurrently, proves the first snapshot
excludes it and the next run detects it. Retry receipt faults prove inbox/notification/receipt roll back together.
ReconciliationMigrationIT seeds real money/events and legacy retry metadata before V12 and checks exact preservation,
immutable cutover inventory and nonfabricated audit. Kafka baseline tests continue to run with a real broker.
