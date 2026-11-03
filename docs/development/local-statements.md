# BL-015 — Local history/statement verification

Issue [#31](https://github.com/ThanhDat19/app-bank/issues/31), branch `feat/BL-015-history-statements`.
BL-014 PR #30 merged; base main `68576b1`. Contract: [ADR-008](../architecture/ADR-008-history-statements.md).

When deliberately running the app locally, Flyway applies V13. Use a synthetic CUSTOMER's own provisioned account:
POST history-snapshots, save Location/id, GET entries with a small limit and follow nextAfterSequence. Create a
new transfer/cash-in after page one, including any trusted test backdating only in a disposable test database:
old pages remain unchanged; a new snapshot includes later committed data.

For dated statements POST local fromDate/toDate/timezone; end is exclusive, not 23:59:59. Opening is before start,
entries inside the effective interval, closing = opening + credits - debits. Future ends are interim snapshots
through DB snapshot time. DST is handled by local start-of-day conversion, not adding 86400 seconds.
Read the header for range/zone/opening/closing; export.csv contains fixed entry rows only.

`bank.statements.max-entries` / `STATEMENT_MAX_ENTRIES` defaults to 10000, valid range 1–100000.
If all-history exceeds the cap, request a smaller dated statement rather than interpreting a truncated history.
Generation timeout is 30s. All snapshot artifacts are immutable and retained; no cleanup job/quota is supplied.

Read-only diagnosis:

```sql
SELECT id,account_id,mode,snapshot_at,status,entry_count,opening_balance,credit_total,debit_total,closing_balance
FROM banking.account_history_snapshots ORDER BY snapshot_at DESC LIMIT 20;
SELECT sequence,posting_id,journal_id,posted_at,side,amount,delta,running_balance
FROM banking.account_history_entries WHERE snapshot_id='<UUID>' ORDER BY sequence LIMIT 100;
```

No new journal, outbox, notification or reconciliation audit is produced by snapshot creation/read/export.
There is no PDF or SSE in this task. Statement rows show separate fee/reversal journals and leave originals intact;
an ADJUSTMENT kind is not automatically labelled a fee because generic internal adjustments share that kind.

Testing uses PostgreSQL Testcontainers and real JWT/JWKS HTTP, not bank_lab. Controlled faults validate incomplete
or oversized snapshots cannot commit and copy failure leaves no partial artifact. A paused AFTER INSERT header
trigger proves concurrent money can commit without being added to the old REPEATABLE READ snapshot.
Tests cover exact signed/20-digit amounts, multi-posting ties, UTC+7 cutoff, 23/25h DST days, frozen/closed reads,
ownership/CSV privacy and public cash-in/transfer/fee/reversal. V12→V13 preserves existing money/event/audit data.
No standalone JAR/browser Swagger or production-sized export benchmark is claimed.
