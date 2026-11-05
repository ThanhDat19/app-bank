# ADR-014 — Safe local telemetry and measured performance baseline

BL-020, issue #43, branch from owner-merged main1548bce. Implemented for owner review; workload/SLO decision follows
measured baseline. [Contract](../api/monitoring.md) · [Run/benchmark](../development/local-monitoring.md).

## Decision

Use the Boot-managed Micrometer Prometheus registry and OpenTelemetry Java SDK. Metrics export only an explicit
allowlist with fixed tag schemas. Local server spans are manually created roots with selected attributes and a bounded
in-memory processor. No tracing bridge, automatic URL/header instrumentation, remote exporter or global SDK is installed.
Automatic http.server.requests observation is disabled; default meters are denied at registry level. The sole scrape
endpoint requires OPERATOR in the existing custom security chain. No permissive actuator chain is introduced.

The observability module does not depend on financial modules. The request filter is registered through its full-app
configuration, not a component automatically included in MVC-only slices. Trusted MVC mapping patterns become route
labels; arbitrary path/query/identity data cannot become route cardinality. Unknown paths/auth failures collapse to
UNMAPPED. Histogram classes/outcomes/methods are bounded, with no exception class or business inputs as labels.

Server trace/span IDs are independent of caller trace headers and correlation IDs. Span processor copies only selected
fields into a finite ring. SDK context/MDC is scoped to the serving thread and restored in finally. No exception payload
is recorded. Async initial dispatch does not stop a timer: listener completion ends the exchange with a CAS guard.
Timeout/error callbacks mark diagnostic outcome, and duplicate callbacks cannot decrement active twice. SSE measures
stream lifetime, not delivery latency; worker threads do not gain implicit financial payload context.

Best-effort collection failure increments a safe diagnostic count and does not replace the business response. Metrics
scrape and trace/cache reads do no SQL. Optional snapshot scheduling defaults off; explicit refresh is OPERATOR-only,
one-at-a-time and read-only. One SELECT samples outbox/notification/external/maker backlogs under a transaction-local
statement timeout. No changes to schema, source tables, money, receipts, outbox delivery state or permissions are made.
Database read failure retains previous counts/timestamp, with availability0 and stale state; no false healthy zero.
Pool acquisition retains datasource limits, so statement timeout is not an overall HTTP deadline.

## Benchmark boundary

PerformanceBaselineIT owns a fresh real PostgreSQL container, booted loopback HTTP application and synthetic signed
JWT/JWKS. It accepts no arbitrary target URL, account IDs or token. Workloads are balance reads and contended unique
transfer creates on one funded pair, at fixed closed-loop concurrency1/4/16. Risk and relay are explicitly disabled,
fee0, telemetry/sampling enabled. Warmups are excluded from latency/throughput. Client latency is send until fully read
body; nearest-rank p50/p95/p99 and throughput/error/timeout counts are reported, with runtime/environment metadata.

All measured attempts enter latency statistics, including errors/timeouts. Throughput denominator is barrier release
until all workers finish and includes worker/request/validation overhead. It is not offered-load or successful-payment
capacity. Correctness assertions require successful expected responses, exact final balances and unique journals/events/
audit. CI runs a small correctness smoke, with no speed pass threshold. Full local measurement is explicit and bounded;
it supplies evidence, not a production SLO or saturation forecast. It does not include representative historical volume,
remote network/TLS, risk RPC, active broker, maker-checker wait, long SSE, restart/failure load or other workload mixes.

## Alternatives and limits

Enabling all native meters or automatic distributed spans would export labels/attributes outside this explicit privacy
contract. The first slice instead keeps a small reviewed schema. The cost is less infrastructure detail: no broker lag,
per-user/per-account breakdown, exporter backend, cross-service causality or durable trace history. Follow-up changes
must add privacy tests and bounded attributes before enabling more instrumentation. Existing logs/correlation and DB/IAM
administrators remain within their existing trust boundaries; this is not a tamper-proof audit or a complete log scrubber.

Sampling0 still permits metrics. The trace buffer is per process and volatile; sampled absence does not prove no request.
Backlog gauges can be stale, exact counts can time out, and histogram quantiles are bucket estimates rather than the
benchmark's exact empirical nearest-rank samples. Alert/workload/SLO choices remain owner decisions after baseline.

## Verification and sources

MonitoringIT uses real HTTP/JWT/PostgreSQL and probes auth, raw-input privacy, fixed-template cardinality, errors,
async once-only completion, read-only snapshot, table-lock timeout and concurrent refresh. Unit tests cover MDC/context,
retention/sampling, denied meters/tags, cache age/failure and percentile mathematics. Existing financial/Kafka/risk/
operations/external/SSE suites remain required. Evidence is in [BL-020 review](../reviews/BL-020.md).

Library integration follows [Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html),
[OpenTelemetry Java SDK](https://opentelemetry.io/docs/languages/java/sdk/) and
[Spring Boot actuator access](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html); versions come from
the existing Boot4.1.2 dependency management (Micrometer1.17.2, OpenTelemetry1.62.0).
