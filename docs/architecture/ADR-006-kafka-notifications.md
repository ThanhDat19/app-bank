# ADR-006 — Kafka transport và in-app notifications

Status: proposed for owner review, BL-013 [issue #27](https://github.com/ThanhDat19/app-bank/issues/27).
Dependency: BL-012 PR #26 merged, base main `ae859cc`.

## Decision

Use Apache Kafka clients 4.2.2 managed by the existing Spring Boot BOM. No additional Spring Kafka
auto-configuration is required. Kafka-enabled configuration owns producer/consumer lifecycle; both Kafka
and outbox scheduling remain disabled by default. Tests use real Kafka 4.2.2 + PostgreSQL 16.14 containers.

KafkaTransport implements the BL-012 OutboxPublisher contract: key = journal aggregateId, value = full
OutboxEvent envelope. Explicit acks=all, idempotence=true and max-in-flight=1; max-block 3s, request timeout
3s, delivery timeout 5s, future wait 6s. Ack outbox only after producer acknowledgement. Unknown outcomes
can still produce duplicates across relay retries; producer idempotence does not replace eventId dedupe.
Application default batch=1 and lease=30s bound one send rather than a long batch under one lease.

## Consumer and database boundary

KafkaNotificationWorker is a single synchronized KafkaConsumer per configured group. Auto-commit off,
max-poll-records=1, earliest reset for a new group. Process each record then explicitly commit offset+1.
If processing is deferred or fails, seek back to that offset and pause the partition until the next poll;
continue polling to maintain group membership. Durable next-attempt timestamps prevent early retries.
Different partitions may progress independently; no later record is committed over a failed record in one partition.

NotificationService verifies schemaVersion/aggregateVersion=1, key and exact envelope/payload against the
local immutable outbox. Unexpected fields, unknown event, changed content and unsupported versions are rejected.
The consumer is intentionally in the same consistency boundary, not a separately deployed projection service.
The verified canonical event hash and eventId enter a durable inbox; one notification per event/bank account
commits with that inbox in one transaction. Ledger application contract resolves customer ledger targets;
system clearing/income accounts receive no customer notification. Consumer never posts money or updates balances.

DB commit before offset commit is safe: a crash/commit failure redelivers but the immutable inbox prevents a
second effect, including concurrent requests. Late journal events append their own notification; they do not
overwrite a newer account state. Reads sort occurredAt/eventId descending, not Kafka arrival time.
Aggregate remains immutable journal/version1. There is no global/per-account event ordering or aggregate state
projection, and principal/fee journals may arrive in either order.

## Retry and DLQ

Transient database failures roll back inbox/notifications and record an attempt in a separate transaction.
Durable backoff starts at 500ms and grows exponentially to 10s; after 3 failed attempts the source record
must be quarantined before its offset can advance. Malformed/untrusted events go directly to quarantine.

DLQ publish occurs outside DB transactions and requires broker acknowledgement. Its body contains only source
topic/partition/offset, SHA-256 payload hash and fixed error code, with key topic:partition:offset. No raw body,
JWT/PII or exception message is copied. After acceptance, persist quarantine then authorize source-offset commit.
If DLT publish, quarantine write or offset commit fails, retain/re-read the source. Duplicate DLT metadata is
possible after a crash; its source-coordinate key identifies it. If PostgreSQL is entirely unavailable, even
attempt tracking can fail; offsets remain uncommitted and resume after recovery.

## API, operations and limits

Notifications are in-app durable records, not email/SMS. CUSTOMER ownership protects bounded account reads;
OPERATOR-only cannot read another customer's inbox through this API. No mark-read, delete or replay endpoint.
No current balance is claimed in a notification.

V11 preserves journal/outbox/lease state, adds inbox, notifications and failure metadata without backfilling.
Existing pending outbox records can later generate notifications when consumption is deliberately enabled.
Single logical notification subscription shares dedupe/failure state. Topic recreation/reset or switching broker
clusters under the same names needs a separate migration plan; source coordinates alone do not identify an incarnation.

Kafka Compose profile is loopback-only single-node KRaft with optional local auto topic creation. No TLS/SASL,
production HA/min-ISR policy, consumer DLQ replay automation, retention pruning or external delivery is implemented.
Broker retention must outlast consumer outages; successfully published events are not automatically re-published
if Kafka data is later deleted. Source Kafka records remain the recovery material for sanitized DLQ entries.

## Primary references

- [Apache Kafka 4.2 producer configuration](https://kafka.apache.org/42/configuration/producer-configs/)
- [Apache Kafka 4.2 consumer configuration](https://kafka.apache.org/42/configuration/consumer-configs/)
- [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/)
