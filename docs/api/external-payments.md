# BL-018b — Durable external payment simulation

Issue #39. CUSTOMER with verified issuer/subject owns commands; OPERATOR-only403, another actor/missing command404.
All responses use no-store and the existing correlation/Problem Details contract. Only synthetic VND/SIM_ routes.

## Prepare, dispatch, query

`POST /api/v1/external-payments`, Authorization Bearer, Idempotency-Key (1..128 supported ASCII characters):

```json
{"accountId":"<owned account UUID>","beneficiary":"SIM_DESTINATION_1","amount":"1000","currency":"VND"}
```

Amount is a canonical positive1..20-digit integer **string**. Extra fields, numeric amount or DTO validation failures400;
service rules422. New preparation requires ACTIVE/provisioned source and configured synthetic clearing account,
available principal+quoted fee, current gross limits, partner health/instance and risk approval (or explicit BYPASSED mode).
Partner health and risk RPC run before the short reservation transaction. Risk validity is checked before/after its locks.
201/Location means PREPARED, not submitted/settled. No partner payment is created at this step.
Same identity/key/payload returns the existing **current command**, before network/risk; changed payload409 IDEMPOTENCY_CONFLICT.
After a lost response retry the original key/payload; a failure can race another successful preparation.

`GET /api/v1/external-payments/{id}` reads durable local state without partner I/O. The view includes id/accountId,
beneficiary, exact amount/currency, state/core version, currently reservedAmount, journalId when settled,
partnerInstance/version/state, sanitized problem, riskOutcome/policy and creation/update times. It omits keys,
raw identity/hash/remote payload and the configured clearing account. Final actual fees use the existing owned
`/api/v1/ledger/accounts/{accountId}/outgoing-charges/{journalId}` resource.

`POST /{id}/dispatch` checks current ACTIVE/provisioning/limits and quote coverage under account locks before claiming.
An increased fee beyond the reservation returns409 EXTERNAL_RESERVATION_STALE without submission: cancel the PREPARED
command and explicitly prepare a new one. A claim commits PREPARED→UNKNOWN **before** HTTP PUT. Only its winner may
submit the immutable ID/intent, with If-Partner-Instance pinned to the stored epoch. Retry/concurrent UNKNOWN dispatch
only GETs the partner. There is no blind automatic resubmission, even after a crash between claim and actual HTTP.
Freeze after the dispatch claim cannot unsend the partner request; it can prevent local settlement until resolved.

`POST /{id}/reconcile` queries only UNKNOWN commands, validates evidence and retries local settlement. It never submits.
Terminal and PREPARED commands return unchanged.200 terminal/PREPARED;202 UNKNOWN. Only matching instance/paymentId/
requestHash/exact amount/currency/beneficiary and valid protocol version/state are accepted. PENDING/version1 remains
UNKNOWN; matching SUCCEEDED/FAILED version2 is definitive. Older observations are ignored, same-version conflicts
retain reservation, and terminal local state never regresses.

## Money and ambiguous results

| Local state | Reservation | Meaning |
| --- | --- | --- |
| PREPARED | Principal+quoted fee | Never claimed for submission; may cancel |
| UNKNOWN | Same nonexpiring reservation | Dispatch claimed, final outcome unresolved or local settlement blocked |
| SUCCEEDED | Released atomically with debit | Exact clearing journal/current fee/outgoing usage/outbox/audit committed once |
| FAILED | Released without posting | Matching definitive partner failure |
| CANCELLED | Released without posting | Canceled before any dispatch claim |

The configured clearing account is an ordinary synthetic provisioned bank account, not the beneficiary's bank account.
Closure of either source or clearing is blocked while PREPARED/UNKNOWN commands reference it, even if the clearing
account currently has zero balance. API closure fails closed; a SQL trigger protects direct writes and requires read committed.
Settlement books an INTERNAL_TRANSFER to that clearing account, so existing journal/fee/outbox/notification/history
formats remain valid. This is simulated accounting, not proof that a real external bank moved money.

Reservation quotes fees at preparation; current policy is authoritative at dispatch and settlement. Pending reservations
do not consume daily **posted** usage. Policy/day/fee/state changes after submission can block local settlement even
after partner success; UNKNOWN, verified evidence and original reservation remain. Do not report this as FAILED or
release money. After the condition is legitimately resolved, reconcile again using the same command. No new risk RPC
is performed for settlement of an already approved durable intent; risk freshness applies at preparation.

404 from partner, timeout, malformed response, redirect, epoch mismatch or missing history never proves failure.
Problems include EXTERNAL_PARTNER_UNAVAILABLE/NOT_FOUND/INSTANCE_CHANGED/INVALID_RESPONSE/CONFLICT. A blocked financial
attempt records EXTERNAL_SETTLEMENT_BLOCKED when the database is available; broader DB/commit failures may return500,
while the previously committed UNKNOWN/reservation still protects funds. Client retry/query must use the same command.
Definitive success that arrives while account is frozen stays UNKNOWN until the existing state/policy checks permit posting.
No forced resolution, auto-expiry, external reversal, callback receiver or background dispatcher/query worker is provided.

`POST /{id}/cancel` only accepts PREPARED (or repeats CANCELLED). It serializes with dispatch. UNKNOWN/other terminal
states409 EXTERNAL_STATE_INVALID. Never use a new idempotency key to retry an ambiguous financial operation.

`GET /{id}/history?limit=50`: owned latest immutable state transitions, descending core version, limit1..100; bad limit422.
Internal atomic CREATING/SETTLING steps are not exposed as durable states; version gaps are expected. Repeated identical
pending/error observations can leave the version unchanged. History retains prior problems and verified state metadata.

## Local operation

Start the BL-018a partner simulator, provision a synthetic clearing account through the normal local workflow, then
intentionally run core with local profile and these settings:

```powershell
$env:EXTERNAL_ENABLED = 'true'
$env:EXTERNAL_PARTNER_PORT = '9092'
$env:EXTERNAL_TIMEOUT_MILLIS = '1000'
$env:EXTERNAL_SETTLEMENT_ACCOUNT = '<synthetic ACTIVE provisioned bank account UUID>'
```

Defaults: disabled, partner literal127.0.0.1:9092,1000ms connect/read timeouts (allowed100..5000). Enabled requires local
profile/nonzero clearing UUID. No caller URL, redirects, cookies/JWT forwarding or remote plaintext fallback. Parsed JSON
response is capped8192 bytes with strict fields/types; socket read timeout is not an overall transaction/slow-peer SLO.
Risk keeps the BL-017 explicit opt-in/default BYPASSED policy; a configured risk outage fails closed before preparation.
Existing commands/history stay readable and PREPARED may cancel when external transport is disabled.

V15 applies on an intentional core startup. Task verification uses only disposable databases; bank_lab local is not
migrated. Do not roll back the combined reservation function while unresolved commands exist. Disable dispatch instead;
retained UNKNOWN may require future authorized manual reconciliation when the simulator lost evidence after restart.
