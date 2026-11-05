# BL-020 — Monitoring and benchmark

Use synthetic local data and current app build. No new migrations. OPERATOR token as in the existing IAM guide.
Default core binds127.0.0.1; only health is public. Do not put an access token into committed Prometheus config or a URL.

```powershell
# Token supplied in this shell, never committed to the repository.
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN" }
Invoke-RestMethod http://127.0.0.1:8080/api/v1/operations/monitoring/snapshot/refresh -Method Post -Headers $headers
Invoke-WebRequest http://127.0.0.1:8080/actuator/prometheus -Headers $headers
Invoke-RestMethod http://127.0.0.1:8080/api/v1/operations/monitoring/traces?limit=20 -Headers $headers
```

Before first refresh, counts are unknown/NaN. To sample periodically, set MONITORING_SCHEDULED=true before startup;
default poll15s, max age60s. unavailable/stale is explicit; zero alone is not a green signal. Any Prometheus deployment
needs a protected OPERATOR authentication arrangement; no collector/dashboard or long-lived credentials are installed here.

Example PromQL for one route (Prometheus job/instance selection must match the actual scraper configuration):

```promql
# Completed requests/s for transfers; error fraction separately from slow requests
sum(rate(bank_http_server_duration_seconds_count{route="/api/v1/transfers"}[5m]))
sum(rate(bank_http_server_duration_seconds_count{route="/api/v1/transfers",outcome="SERVER_ERROR"}[5m]))
  / clamp_min(sum(rate(bank_http_server_duration_seconds_count{route="/api/v1/transfers"}[5m])), 1e-9)
histogram_quantile(0.95, sum by (le) (rate(bank_http_server_duration_seconds_bucket{route="/api/v1/transfers"}[5m])))
histogram_quantile(0.99, sum by (le) (rate(bank_http_server_duration_seconds_bucket{route="/api/v1/transfers"}[5m])))
# Diagnostic candidates, NOT agreed production alert/SLO thresholds
bank_monitoring_available == 0
bank_monitoring_snapshot_age > 60
bank_backlog_external_unknown > 0
```

No-request denominator protection prevents divide-by-zero; it does not imply valid SLO evidence during idle windows.
Separate CLIENT_ERROR/TIMEOUT from SERVER_ERROR according to the chosen workload/error budget. Keep SSE/events route
separate because its duration is connection lifetime. Fixed buckets yield approximate histogram quantiles.

## Reproduce the isolated measurement

Do not start or point a load tool at bank_lab. The harness creates and destroys its own PostgreSQL, signed JWT/JWKS,
Spring application and synthetic funded accounts. It accepts no target/token/real account parameters.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$revision = git rev-parse HEAD
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=D:\App\app-bank\.m2-local' '-Dspring.test.context.cache.maxSize=1' `
  '-Dtest=BenchmarkSummaryTest,TelemetryPolicyTest' '-Dit.test=PerformanceBaselineIT' `
  '-Dbank.benchmark.full=true' "-Dbank.benchmark.revision=$revision" verify
```

The offline command assumes dependencies have been downloaded. Without -o, use the same workspace Maven repository.
Full profile has6 stages:400 balance reads and150 contended unique transfer creates, each at concurrency1/4/16.
Warmup50 reads/20 creates per stage;1650 measured attempts,210 excluded warmups,510 total transfers including warmup.
One HTTP client is reused; latency begins after request construction and ends after consuming the body. Throughput
wall time includes all worker/request/validation overhead. All attempts, including errors/timeouts, enter percentiles.
Nearest-rank formula: sorted sample index ceil(q*N)-1; no extrapolation. HTTP/transport/timeout/body errors are separate.

Output `.tools/BL-020-benchmark.json` contains no URLs, tokens, actor/account IDs or keys. It includes code revision,
runtime/OS/CPU/heap, stage sample sizes, latency/rate/error statistics and final aggregate financial closure. The committed
baseline/report is linked from [review](../reviews/BL-020.md). Full verify/CI use a small smoke profile (concurrency1/4,
8 measured requests per stage) to check harness correctness; there are no timing pass thresholds.

This is a short closed-loop local sample under instrumentation, not saturation testing. Dataset/history is small,
risk/relay disabled, fee0, no TLS/remote RTT/production traffic. Owner chooses representative mixes, dataset, durations,
failure scenarios, repetitions and SLOs after reviewing the baseline. No production capacity number is inferred.
