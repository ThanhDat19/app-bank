# BL-013 — Local Kafka, notifications và recovery

Issue [#27](https://github.com/ThanhDat19/app-bank/issues/27), branch `feat/BL-013-kafka-notifications`.
Main dependency `ae859cc`, BL-012 đã merge PR #26. No broker/email account or cloud service is needed.

## Optional local startup

Only run these commands when you deliberately want to start the local broker and migrate bank_lab with the app.
Implementation/testing itself uses disposable Testcontainers and does not change bank_lab.

```powershell
. .\scripts\Use-LocalEnvironment.ps1
docker compose --profile messaging up -d kafka
$env:KAFKA_ENABLED = 'true'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:OUTBOX_RELAY_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

Compose uses apache/kafka-native:4.2.2, one KRaft broker/controller, loopback 9092 and kafka-data volume.
Local auto topic creation is enabled with 3 partitions. Default topics:
`bank.ledger.journal-posted.v1`, `bank.notifications.dlq.v1`; group `bank-notifications-v1`.
Outside this local profile, provision topics/replication/retention explicitly before enabling clients.
`docker compose --profile messaging config --quiet` validates syntax without starting services or printing secrets.

Default KAFKA_ENABLED/OUTBOX_RELAY_ENABLED are false. Kafka true / relay false can consume previously published
records without publishing pending outbox. Relay true / Kafka false fails closed because no publisher exists.
No connection clients are created with Kafka disabled. Topics must differ; bootstrap/topic/group settings
are server configuration, never request fields.

| Property | Default |
| --- | --- |
| bank.kafka.enabled | false |
| bank.kafka.bootstrap-servers | localhost:9092 |
| bank.kafka.events-topic | bank.ledger.journal-posted.v1 |
| bank.kafka.dlq-topic | bank.notifications.dlq.v1 |
| bank.kafka.notification-group | bank-notifications-v1 |
| bank.kafka.notification-poll-millis | 500 |
| bank.outbox.relay.batch-size | 1 |
| bank.outbox.relay.lease-millis | 30000 |

## Observe and recover

Perform a synthetic cash-in or internal transfer using existing Swagger guides, then read the owned account's
notifications API. Eventual notification delivery is independent of the successful financial HTTP response.
Read-only SQL for diagnosis:

```sql
SELECT state,count(*) FROM banking.outbox_deliveries GROUP BY state;
SELECT count(*) FROM banking.notification_inbox;
SELECT event_id,account_id,journal_id,kind,occurred_at
FROM banking.notifications ORDER BY occurred_at DESC,event_id DESC LIMIT 100;
SELECT source_topic,source_partition,source_offset,attempts,available_at,quarantined,error_code
FROM banking.notification_failures ORDER BY source_topic,source_partition,source_offset;
```

Broker down: committed money remains valid; outbox stays PENDING/LEASED for retry. Restore broker without
deleting its volume, then relay resumes. Consumer DB failure: inbox/notification rollback, source offset is
not committed; durable backoff gates retries. After 3 failures, a broker-acknowledged sanitized DLT and durable
quarantine allow that partition to progress. DLT failure does not authorize committing the source offset.

Quarantine is not automatically replayed after fixing a bug. Use the DLQ source coordinates to inspect the
original retained Kafka record under operator control, then review a replay procedure to a new source offset.
Never edit financial journals, delete inbox rows or mark source offsets consumed to hide failures.
Malformed records are never executed as arbitrary postings. The consumer checks the local immutable event
before resolving ledger targets. Do not recreate/reset topics or change cluster incarnation while reusing
the same failure-coordinate state without a migration plan. No public replay endpoint is implemented.

## Testing boundary

KafkaNotificationIT starts real Kafka 4.2.2 and PostgreSQL 16.14: producer acknowledgement, owned JWT HTTP reads,
duplicate/concurrent/restart effects, paused/unpaused broker recovery, malformed/conflicting/versioned events,
durable retry/exhaustion/DLQ and late journal ordering. DLQ failure is injected at the publisher boundary;
accepted-before-offset-commit is simulated by committing DB first, then delivering through Kafka.
NotificationMigrationIT verifies V10 money/outbox payload/active lease survive V11 with empty notification tables.
There is no production cluster, process-kill drill, TLS/SASL or external email/SMS test here.
