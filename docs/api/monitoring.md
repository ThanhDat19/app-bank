# BL-020 — Monitoring baseline

Issue [#43](https://github.com/ThanhDat19/app-bank/issues/43), dependency PR #42 merged; no schema migration.
Metrics/traces/snapshots are diagnostics, not authoritative financial receipts or audit. See [ADR-014](../architecture/ADR-014-observability.md)
and [benchmark/run guide](../development/local-monitoring.md).

## HTTP and access

| Method/path | Contract |
| --- | --- |
| GET `/actuator/prometheus` | Verified OPERATOR, Prometheus text/OpenMetrics negotiation; no-store; memory-only scrape |
| GET `/api/v1/operations/monitoring/traces?limit=50` | OPERATOR, latest sampled completed HTTP spans; limit1–100, volatile bounded buffer |
| GET `/api/v1/operations/monitoring/snapshot` | OPERATOR, cached aggregate diagnostic; no database query |
| POST `/api/v1/operations/monitoring/snapshot/refresh` | OPERATOR, one read-only database snapshot; no body or financial mutation |

Anonymous401, CUSTOMER403. Invalid bounds400; concurrent refresh409 MONITORING_REFRESH_BUSY.
Read failure deliberately returns200 diagnostic JSON: `available=false`, `stale=true`, `problem=MONITORING_SNAPSHOT_UNAVAILABLE`,
old counts/lastSuccessAt preserved. It does not report successful freshness. Initially lastAttemptAt/lastSuccessAt are null,
counts empty and availability0; nothing has been sampled. GET is cached and cannot refresh the database accidentally.
All API responses no-store, existing Problem Details/correlation rules apply. Health remains public with status/probe group
metadata only; env/configprops/heapdump/metrics/beans and all other management routes remain denied.

## Telemetry fields and bounds

Every initial HTTP request gets a server-generated `X-Trace-ID` (32 lowercase hex), independent of X-Correlation-ID.
Incoming traceparent/baggage/X-Trace-ID are not used as parent/attributes. MDC traceId/spanId are scoped/restored on the
request thread. Existing X-Correlation-ID remains the BL-002 caller-controlled field; never put sensitive values in it.
The new collector does not change existing domain logs/access-log configuration or promise sanitization of arbitrary logs.

| Span field | Value |
| --- | --- |
| traceId / spanId | Server-generated IDs, no caller identity |
| method | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS/OTHER |
| route | Trusted MVC route template or explicit health/scrape route; unknown maps to UNMAPPED |
| status / outcome | Observed completion status; SUCCESS/CLIENT_ERROR/SERVER_ERROR/TIMEOUT |
| completedAt / durationMillis | SDK end time and elapsed server span duration |

No URL, query, headers, JWT, actor/account IDs, key, amount, body, exception message or free-form business input is
collected. Route catalog accepts at most256 declared templates plus UNMAPPED. SDK spans have four attributes, max200
characters each, fixed name bank.http.request and fixed service resource. Sampling default0.1, configurable0–1; no
remote exporter, global SDK registration, baggage propagation or automatic raw-URL HTTP span instrumentation.
Trace buffer default200/max2000 completed sampled spans, newest first, lost on restart. It is not durable audit/export.

Async/SSE requests remain active until completion; completion/error/timeout callbacks finish once. A transport error is
classified as SERVER_ERROR/status500 for diagnostics, even if bytes were already committed. SSE latency represents
connection lifetime; exclude the events route from ordinary request latency queries. This is local core HTTP tracing,
not a cross-service gRPC/Kafka/external partner trace. Sampling0 still records metrics and supplies generated IDs.

## Exported metrics

An allowlist rejects all native/default meters and unexpected tag keys/values. New meters require explicit review.
No trace/identity/correlation IDs in metric labels. The duration histogram alone uses method/route/status-class/outcome.

| Prometheus series prefix | Meaning/unit |
| --- | --- |
| `bank_http_server_duration_seconds_{bucket,count,sum,max}` | Server request duration, fixed buckets5/10/25/50/100/250/500ms,1/2/5/10/30/120s and +Inf |
| `bank_http_server_active` | Requests currently in flight, includes async/SSE |
| `bank_runtime_heap_used`, `bank_runtime_heap_max` | JVM heap bytes; not container RSS |
| `bank_runtime_threads` | Live JVM thread count |
| `bank_monitoring_telemetry_failures` | Cumulative best-effort collector failures since process start; collector failure does not replace business response |
| `bank_monitoring_available` | Last refresh succeeded1, never sampled/last refresh failed0; not a database health assertion |
| `bank_monitoring_snapshot_age` | Seconds since last successful refresh; NaN before first success |
| `bank_backlog_outbox_pending`, `bank_backlog_outbox_leased` | Counts of PENDING/LEASED deliveries |
| `bank_backlog_outbox_oldest` | Age seconds of oldest non-DELIVERED event, zero when none |
| `bank_backlog_notifications_retry`, `bank_backlog_notifications_quarantined` | Current notification failure rows by quarantine flag |
| `bank_backlog_external_unknown`, `bank_backlog_external_prepared` | Durable unresolved external commands |
| `bank_backlog_operations_pending` | PENDING maker-checker intents, including not-yet-eligible targets |

Backlog gauges are NaN before first successful read and retain previous values on failure. Check availability AND age;
old/zero values alone cannot prove absence of work. Each JSON snapshot is one cached record; an overlapping Prometheus
scrape can observe gauges around a refresh boundary. Operational counts are not payment-finality or corruption proofs.

The read uses one SELECT with a PostgreSQL statement timeout (default500ms), READ COMMITTED/read-only REQUIRES_NEW.
At most one refresh runs; there is no work queue. Statement timeout includes waiting on table locks, but datasource
connection acquisition follows its existing pool timeout; it is not an end-to-end500ms guarantee. No writes/migrations
or automatic remediation. Exact count queries may time out on large tables; retain stale state rather than invent zeros.

## Configuration

| Property / environment | Default / accepted range |
| --- | --- |
| bank.monitoring.trace-sampling / MONITORING_TRACE_SAMPLING |0.1 / finite0–1 |
| bank.monitoring.trace-capacity / MONITORING_TRACE_CAPACITY |200 /1–2000 |
| bank.monitoring.scheduled / MONITORING_SCHEDULED |false; periodic snapshots opt-in |
| bank.monitoring.poll-millis / MONITORING_POLL_MILLIS |15000 /5000–3600000 when scheduled |
| bank.monitoring.snapshot-max-age-millis / MONITORING_SNAPSHOT_MAX_AGE_MILLIS |60000 /100–3600000 |
| bank.monitoring.statement-timeout-millis / MONITORING_STATEMENT_TIMEOUT_MILLIS |500 /50–2000 |

No sampling worker writes financial data. Manual refresh is available while periodic sampling is disabled. Histogram
boundaries are measurement buckets, not accepted SLOs. Broker-admin lag, separate Prometheus/Grafana/OTLP deployment,
alert delivery, distributed tracing and production workload/capacity/SLO certification remain outside this slice.
