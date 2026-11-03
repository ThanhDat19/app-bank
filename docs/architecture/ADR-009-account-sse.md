# ADR-009 — Account SSE hints and REST resynchronization

BL-016, issue #33. Dependency: BL-015 owner merged PR #32; branch starts at main `f6f1c0e`.

## Decision

Expose an owned account channel through Spring MVC `SseEmitter`. It sends an initial `resync`
event on **every** connection and a further hint when a read-only sample changes. The event contains
only accountId and a fixed reason. Clients read official REST resources for the actual state.
SSE never runs a command or confirms money movement. Financial atomicity/idempotency remains in
the existing command services and PostgreSQL transaction boundary.

No durable replay cursor is invented from UUID, postedAt, notification occurredAt or sequence allocation.
There are no SSE `id` fields; Last-Event-ID is ignored. Reconnect requires fresh REST reads even when
the client supplies a last ID. A process restart, network loss or coalesced samples can lose hints.
Client refreshes after reconnect and periodically while connected; SSE is an acceleration of REST polling.

## Sample and ownership

Each sample is a short read-only REPEATABLE READ transaction with a 3-second transaction/query timeout:

- Owned account lifecycle version and provisioning marker. Version catches freeze/unfreeze between samples.
- Count of POSTED account-side postings, not their maximum ID/time; later backdated commits change the count.
- Count and total of current unexpired ACTIVE holds, evaluated by database statement time. Passive expiry
  changes the sample without writing an expiry transition.
- Count of committed durable notifications, including late deliveries whose occurredAt is outside the latest list.

Posting/notification source rows are immutable. Counters are recomputed reads, not new mutable projections.
Different commits can coalesce into one hint; transient hold changes can be absent from both samples.
Source repair, incomplete event pipelines and general database corruption are reconciliation concerns.
Unprovisioned/frozen/closed accounts can connect. An unprovisioned account's balance REST call still returns
its existing 422 response. No transaction, DB lock or JDBC connection is held during a network write or sleep.

CUSTOMER and issuer/subject ownership are checked before returning the emitter and on every sample.
The worker gets a fresh explicitly captured Spring Security context via DelegatingSecurityContextRunnable;
the wrapper restores its prior context. A validated correlation ID is propagated through MDC and restored
in finally. JWT bytes and private failure details are never put in frames or logs. The verified authentication
is retained in memory only while its worker is active; authorization is the token's signed role grant until exp.
There is no online logout/revocation introspection or background token refresh.

## Bounds and transport

Defaults: poll 2000ms, connection lifetime 120000ms, 32 connections per process, 2 per issuer/subject.
Configuration validates poll 100..30000ms, lifetime 500..300000ms, total 1..128 and actor 1..total.
These low minima support local tests; production workload tuning belongs to BL-020.
Every unchanged sample sends a heartbeat comment. Retry guidance is 5000ms.
The worker stops at min(token exp, connection lifetime), checks expiry again after a query and before each
new frame, and releases on EOF/write error, callback cancellation, failure or shutdown. Already-started TCP
writes may finish after expiry. Servlet timeout provides a further lifetime+1000ms cleanup signal.

A fixed maximum worker count with a SynchronousQueue accepts no queued work. Admission and per-identity
counts are synchronized; error before admission consumes no slot. Closed/slow transports retain their slot
until the worker exits, preventing reconnects from creating unbounded blocked writers. Servlet blocking
writes cannot promise a strict slow-client write deadline; the bounded workers can still all be occupied.
Capacity rejection is 409 SSE_CONNECTION_LIMIT, with client backoff. Rejected tasks release their admission.
Limits are per JVM, not cluster-wide. Queries scan the account's indexed history; there is no production-scale
cost/SLO claim. Count-scan cost, connection admission request rate and proxy socket timeouts require deployment
workload/rate-limit configuration before scaling this simulator.

Responses use no-store and X-Accel-Buffering:no. Deploying proxies must disable stream buffering and have
compatible idle timeouts; no automatic proxy configuration is implied. Browser clients use authenticated
fetch streaming because native EventSource cannot supply this API's Authorization header. No query-token,
cookie/session auth or new CORS permissions. Shutdown closes workers; clients recover through REST.

No migration, outbox/broker change, source write, new audit entry, per-journal event feed, exactly-once,
guaranteed delivery, multi-account ordering or financial snapshot is introduced.

References: [Spring MVC async/SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html),
[Spring Security concurrency](https://docs.spring.io/spring-security/reference/servlet/integrations/concurrency.html).
