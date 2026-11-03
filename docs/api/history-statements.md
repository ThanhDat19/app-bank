# BL-015 — History/Statement API

All routes require CUSTOMER + current account ownership and return no-store. OPERATOR-only is forbidden.
Creating an artifact does not move money. Location/header URI is shared by history and dated statement snapshots.

| Route | Behaviour |
| --- | --- |
| POST `/api/v1/accounts/{accountId}/history-snapshots` | No body; snapshot all visible POSTED account entries |
| POST `/api/v1/accounts/{accountId}/statements` | JSON fromDate/toDate/timezone; date window snapshot |
| GET `/api/v1/accounts/{accountId}/statements/{id}` | Immutable header |
| GET `/api/v1/accounts/{accountId}/statements/{id}/entries?afterSequence=0&limit=100` | Stable ascending sequence page |
| GET `/api/v1/accounts/{accountId}/statements/{id}/export.csv` | UTF-8 entry CSV attachment |

Statement request:

```json
{"fromDate":"2026-10-01","toDate":"2026-11-01","timezone":"Asia/Ho_Chi_Minh"}
```

201 + Location returns id, accountId, mode HISTORY/STATEMENT, currency VND, timezone, date labels/UTC boundaries,
effectiveToAt, snapshotAt, status READY, openingBalance, creditTotal, debitTotal, closingBalance, entryCount,
completedAt and interim. Money fields are signed/exact decimal strings; totals are nonnegative.
History has null date boundaries, UTC label and opening zero. Statement date end is exclusive; future end caps at
snapshotAt and interim=true. Entirely future start is rejected. Range is 1–366 local days, years 1–9999.

Entry page:

```json
{"snapshotId":"<uuid>","items":[{"sequence":1,"postingId":"<uuid>","journalId":"<uuid>","referenceId":"<uuid>","kind":"INTERNAL_TRANSFER","postedAt":"2026-10-01T01:00:00Z","side":"DEBIT","amount":"1000","delta":"-1000","runningBalance":"9000"}],"nextAfterSequence":1}
```

nextAfterSequence is present only when another page exists; pass it back with the same owned account/snapshot.
Limit 1–200/default 100, afterSequence >=0. Ordering is postedAt/journalId/postingId at creation; later commits,
including backdated ones, require a new snapshot and do not change page membership. One posting is one row,
so a journal with multiple account lines can appear more than once. No counterparty identity/current balance is exposed.

CSV header: `sequence,postedAt,journalId,referenceId,kind,side,amount,delta,runningBalance`.
Header metadata is obtained separately; filename is `statement-<snapshot UUID>.csv`, no client filename parameter.
Snapshots are unsigned read artifacts and do not provide a PDF/certification. Signed historical balances and
20-digit amounts stay exact in API/CSV bytes; client spreadsheet numeric conversion is outside the export contract.

401 missing/invalid token; 403 wrong role; 404 ACCOUNT_NOT_FOUND outside ownership scope, or
ACCOUNT_SNAPSHOT_NOT_FOUND for unknown/cross-account snapshot binding. FROZEN/CLOSED remain readable.
422 LEDGER_NOT_PROVISIONED / LEDGER_LINK_INCONSISTENT, STATEMENT_TIMEZONE_INVALID / STATEMENT_RANGE_INVALID,
HISTORY_PAGE_INVALID / HISTORY_TOO_LARGE. Bad JSON/UUID/date/integer uses shared 400 contract.
No partial artifact survives a cap/persistence/commit failure. POST is non-idempotent: lost-response retry can
create another read snapshot. There is no create-idempotency key, delete/list-all or public repair endpoint.
