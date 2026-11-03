# BL-012 — Outbox local và recovery

Issue [#25](https://github.com/ThanhDat19/app-bank/issues/25), branch `feat/BL-012-transactional-outbox`.
Contract: [ADR-005](../architecture/ADR-005-transactional-outbox.md).

## Behaviour and configuration

App migration V10 creates `banking.outbox_events` and `banking.outbox_deliveries`.
Normal public cash-in/transfer/reversal/capture writes create events with their journal automatically.
GET/replay, account provisioning and hold place/release/expiry do not create new journal events.
No public outbox API or arbitrary event publishing endpoint is added; Swagger banking operations stay unchanged.

Scheduling default: `bank.outbox.relay.enabled=false` (`OUTBOX_RELAY_ENABLED=false`).
Do not enable before supplying a real OutboxPublisher adapter. Startup fails if enabled without a publisher;
a logger/no-op is not accepted as a delivery implementation.

| Property | Default | Bounds / contract |
| --- | --- | --- |
| bank.outbox.relay.batch-size | 1 | 1–100 events; BL-013 uses a single bounded Kafka send per lease |
| bank.outbox.relay.lease-millis | 30000 | 1–300000 ms |
| bank.outbox.relay.poll-millis | 1000 | Spring fixed delay, positive milliseconds |

Adapter publish must return after durable transport acceptance and have bounded I/O timeouts. Keep worst-case
batch duration below lease duration. BL-012 supplies transport-neutral relay only; BL-013 adds Kafka.
Internal application API `OutboxRelay.deliverBatch(publisher,batchSize,leaseMillis)` rejects an ambient transaction.

Each envelope contains eventId, aggregateId (journalId), aggregateType LedgerJournal, aggregateVersion 1,
eventType LedgerJournalPosted, schemaVersion 1, occurredAt and JSON payload. Example payload:

```json
{"journalId":"<uuid>","referenceId":"<uuid>","kind":"INTERNAL_TRANSFER","currency":"VND","postings":[{"ledgerAccountId":"<uuid>","side":"DEBIT","amount":"100000"},{"ledgerAccountId":"<uuid>","side":"CREDIT","amount":"100000"}]}
```

## Read-only diagnosis

After deliberately starting the app/migrating your local database, inspect using your database client:

```sql
SELECT state,count(*),min(available_at),min(lease_until)
FROM banking.outbox_deliveries GROUP BY state;

SELECT e.event_id,e.aggregate_id,e.event_type,e.aggregate_version,e.schema_version,
       d.state,d.attempts,d.available_at,d.lease_until,d.delivered_at
FROM banking.outbox_events e JOIN banking.outbox_deliveries d USING(event_id)
ORDER BY e.occurred_at,e.event_id LIMIT 100;
```

PENDING is expected while relay is disabled. A crash leaves LEASED rows; after lease_until, another worker
may claim them with a new token and increased attempts. Do not mark DELIVERED manually to suppress a backlog,
reuse stale tokens, delete events or rewrite posted journals. Fix the adapter/transport and resume relay.
Interrupted processing leaves remaining batch claims recoverable after expiry. Failure payloads/messages are
not persisted or copied to logs by relay; attempts/backoff remain visible in the delivery row.

No public API can purge/reset/replay events. DELIVERED rows are terminal. A new destination or replay policy
needs a separate delivery/subscription design; this table currently represents one logical destination.

## Verification

Tests use PostgreSQL 16.14 Testcontainers, not bank_lab. `OutboxIT` covers atomic visibility/rollback,
deferred receipt rollback, disjoint concurrent claims, crash/stale-token fencing, backoff, transport accepted
before ack and ack persistence failure, immutable envelopes/terminal delivery and ambient-transaction rejection.
`OutboxMigrationIT` checks V9 posted data survives V10 with no backfill and forged payload is rejected.
`OutboxConfigurationTest` checks default-disabled scheduling and missing publisher fails closed.
The existing banking/security/migration/packaging baseline must continue to pass.
