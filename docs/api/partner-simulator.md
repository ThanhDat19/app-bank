# BL-018a — Local external partner simulator

Issue #37, first BL-018 dependency. This is a separate executable HTTP partner process, **not** a core payment API.
It does not create accounts, reserve/debit money, invoke risk, or write financial receipts. Durable core states,
UNKNOWN reservations and settlement/reconciliation are the next BL-018b increment, after owner review/merge.

## Run

```powershell
.\mvnw.cmd -B -ntp package
$env:PARTNER_SIMULATOR_PORT = '9092'
java -jar target/core-banking-service-0.1.0-SNAPSHOT-partner-simulator.jar
```

No DB/IAM environment is required. The listener binds only127.0.0.1. There is no remote authentication/TLS,
real bank integration or production partner guarantee. Use only synthetic tokens/amounts on a trusted local machine.
By default operations start PENDING/version1 and automatically become SUCCEEDED/version2 after250ms.
Test controls default off. There is no simulated fee; amounts are exact canonical VND principal strings.

## HTTP contract

`GET /health` →200 `{ "instanceId":"<process UUID>","status":"UP" }`.

`PUT /payments/{canonical UUID}`, Content-Type:application/json:

```json
{"requestHash":"<64 lowercase hex>","amount":"99999999999999999999","currency":"VND","beneficiary":"SIM_DESTINATION_1"}
```

All four fields are required strings; extra/duplicate fields, trailing JSON, numeric amounts, zero, negative,
fractional, leading-zero or >20-digit values reject400. Beneficiary must match `SIM_[A-Z0-9_-]{1,40}`; this is
an opaque synthetic route token, never a real account number/name. Missing/wrong media type415; body>4096 bytes413.
The simulator treats requestHash as an opaque immutable binding, not an authenticated proof of a core command.

200 returns the current observation of the accepted operation:

```json
{"instanceId":"<process UUID>","paymentId":"<UUID>","requestHash":"<64 lowercase hex>","amount":"100","currency":"VND","beneficiary":"SIM_DESTINATION_1","state":"PENDING","version":1}
```

Within one process, same payment ID + exact normalized intent is idempotent; return its current observation.
Changed intent409 IDEMPOTENCY_CONFLICT. Concurrent requests serialize operation creation. The original PUT response
may be PENDING even if a later query is already terminal. No201/new-vs-replay distinction or event delivery guarantee.

`GET /payments/{id}` →200 latest authoritative observation for this **simulator process**; missing404
PAYMENT_NOT_FOUND. State is PENDING→SUCCEEDED or FAILED, version1→2. Terminal state never changes; repeated
matching terminal observations retain version2. Every JSON response has no-store. Errors expose fixed code and
instanceId only, no exception/input echo. Unsupported method405; unknown route404; malformed UUID400.

## Controlled failures and reorder fixtures

Start a separate synthetic test process with `PARTNER_SIMULATOR_TEST_CONTROLS=true`. Automatic success is disabled;
accepted operations stay PENDING until explicitly resolved:

- `POST /test/payments/{id}` JSON `{"state":"SUCCEEDED"}` or `{"state":"FAILED"}`. Same terminal retry200;
  opposite terminal409 TERMINAL_CONFLICT. These paths are404 when controls are disabled.
- Header `X-Simulator-Delay-Millis: 0..2000` delays the response **after** acceptance/observation capture. A client
  timeout/disconnect does not roll back acceptance. Header on a normal process rejects400 TEST_CONTROLS_DISABLED.
- `GET /payments/{id}?version=1` or `version=2` returns a retained old observation only in controlled mode. Latest
  GET remains unchanged. Unknown query400; not-yet-existing version404 VERSION_NOT_FOUND.
- Combine delayed PUT/version1 with a fast terminal POST/version2 to deliver observations out of order. Duplicate
  same-ID submissions and repeated queries provide duplicate delivery fixtures without creating another operation.

## Recovery constraints for BL-018b

State/deduplication/history are in memory and last only for this process. Restart creates a new instanceId and
loses all operations. This is a deliberate loss fixture, not durable production idempotency. A404, timeout, instance
change, unavailable query or stale PENDING response is **not** proof that an external payment failed.
Core must persist its command/intent and first observed partner instance, retain UNKNOWN and its reservation on
ambiguity, query rather than blindly submit again, and require definitive matching terminal evidence before settling
or releasing funds. Never automatically resubmit an ambiguous command across process restart, even with the same ID.
If the partner lost evidence, manual reconciliation is required; this simulator offers no recovery proof after loss.
BL-018b must bind instance/paymentId/hash/amount/beneficiary, ignore stale versions, handle conflicting terminal evidence,
keep network outside DB transactions, and protect settlement against duplicate/concurrent observations.

Bounds: 10000 retained operations, max2 immutable observations each; capacity503 SIMULATOR_CAPACITY (existing IDs
still replay). Four workers,32 queued tasks, listen backlog32; delayed requests occupy workers. Under saturation a
connection can fail rather than receive a JSON error. Slow-body/socket timeouts and deployment rate limiting are not
configured; no production load/DoS SLO claim. Ctrl+C stops listener/timer/workers; no persistent state remains.
