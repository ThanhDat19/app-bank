# BL-016 — Local SSE verification

Use the existing local database/IAM setup only when you intentionally run the app. This implementation's
automated verification uses disposable PostgreSQL Testcontainers and the retained Kafka test baseline;
it does not migrate or modify the local bank_lab database.

Optional settings: SSE_POLL_MILLIS=2000, SSE_LIFETIME_MILLIS=120000,
SSE_MAX_CONNECTIONS=32, SSE_MAX_PER_ACTOR=2. Limits are per process; poll minimum100ms is for tests,
not a demonstrated production capacity recommendation. Kafka/outbox enablement stays independent.
SSE observes financial state even when Kafka is off; notification hints require the existing notification pipeline.

With a synthetic CUSTOMER token in the environment, connect from a local terminal (tokens stay out of URLs):

```powershell
curl.exe --no-buffer --header "Accept: text/event-stream" --header "Authorization: Bearer $env:BANK_CUSTOMER_TOKEN" "http://127.0.0.1:8080/api/v1/accounts/$env:BANK_ACCOUNT_ID/events"
```

Expect CONNECTED then heartbeat comments. Use another terminal for an authorized synthetic cash-in/hold/
transfer; expect STATE_CHANGED and read official REST state. Stop curl, make a change, then reconnect with
any Last-Event-ID: still expect CONNECTED and refresh REST. The stream ends after exp or lifetime.
Never use a new idempotency key to recover a lost command response. Do not copy tokens to logs/docs.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=D:\App\app-bank\.m2-local' '-Dspring.test.context.cache.maxSize=1' clean verify
```

Focused integration: add `'-Dit.test=AccountEventsIT'` (unit tests still run).
Review [API recovery contract](../api/account-events.md) and [bounds/limitations](../architecture/ADR-009-account-sse.md).
