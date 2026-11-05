# BL-014 — Reconciliation & Audit API

All routes require verified OPERATOR role and return `Cache-Control: no-store`. CUSTOMER-only is forbidden.
No request accepts actor, source rows, repair instructions or arbitrary SQL.

| Route | Behaviour |
| --- | --- |
| POST `/api/v1/operations/reconciliation/runs` | No body. 201 + Location; each call creates a distinct immutable report |
| GET `/api/v1/operations/reconciliation/runs/{id}` | 200 report + counts grouped by code/severity |
| GET `/api/v1/operations/reconciliation/runs/{id}/findings?afterId=0&limit=100` | Ascending finding IDs within one run, nextCursor → next afterId |
| GET `/api/v1/operations/audit-events?beforeId=9223372036854775807&limit=100` | Descending audit IDs, nextCursor → next beforeId |

Report example:

```json
{"run":{"id":"<uuid>","actorIssuer":"<verified issuer>","actorSubject":"<verified subject>","actorType":"OPERATOR","snapshotAt":"2026-11-03T00:00:00Z","graceSeconds":300,"status":"WARNING","errorCount":0,"warningCount":1,"completedAt":"2026-11-03T00:00:01Z"},"summary":[{"code":"OUTBOX_DELIVERY_LAG","severity":"WARNING","count":1}]}
```

Page example (nextCursor null when exhausted):

```json
{"items":[{"id":1,"code":"OUTBOX_DELIVERY_LAG","severity":"WARNING","resourceType":"OUTBOX_EVENT","resourceId":"<uuid>"}],"nextCursor":null}
```

CLEAN = no findings; WARNING = eventual pipeline/backlog/unknown-history warnings only; MISMATCH = structural errors.
Report is a snapshot and is not updated when the pipeline catches up. It does not repair source data or post money.
Run/findings/audit commit together. A timeout/persistence failure returns the common error contract and leaves no
partial RUNNING report. POST is not idempotent: after a lost response, retry may create another report.

401 invalid/missing JWT; 403 wrong role; 404 RECONCILIATION_NOT_FOUND; 409 RECONCILIATION_IN_PROGRESS for overlap.
422 RECONCILIATION_PAGE_INVALID when limit outside 1–200, negative afterId or nonpositive beforeId; malformed
UUID/integer uses shared 400 contract. Missing report is checked before finding-page bounds.

Audit entries contain id, actorIssuer, actorSubject, actorType, action, resourceType, resourceId, occurredAt.
Central journal/report audit starts V12. V16 adds OPERATION_REQUESTED/APPROVED/REJECTED/CANCELLED,
validated against immutable [maker-checker history](maker-checker.md), with no historical approval backfill.
Audit contains required identity metadata for operations; it is not public
customer data. No arbitrary create/delete audit API. Audit IDs can commit out of order: refresh browsing as needed,
do not use beforeId/IDs as a reliable incremental export/checkpoint. Immutable per-run findings do not have this issue.

See [ADR-007](../architecture/ADR-007-reconciliation-audit.md) for exact rules, cutover exemptions and limitations.
