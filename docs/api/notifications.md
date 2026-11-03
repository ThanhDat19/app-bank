# BL-013 — In-app journal notifications

`GET /api/v1/accounts/{accountId}/notifications?limit=50`

CUSTOMER role and ownership of accountId required. Both incoming and outgoing journal participants receive
one record per event/customer bank account; fee journals are separate records. System clearing/income accounts
do not get notifications. No email or SMS is sent.

200 + `Cache-Control: no-store`, list of at most 100 entries, default 50:

```json
[{"eventId":"<uuid>","accountId":"<uuid>","journalId":"<uuid>","kind":"INTERNAL_TRANSFER","occurredAt":"2026-11-02T04:00:00Z"}]
```

Newest occurredAt/eventId first; a late-arriving event retains its original journal time. A repeated event
does not create another record. This bounded latest list does not promise full history pagination, statement
semantics or synchronous delivery. Notification contains no current balance, JWT, identity issuer/subject,
customer display name, account number or operation reason. Kafka arrival does not mutate money.

401 missing/invalid token; 403 OPERATOR-only or wrong role; 404 ACCOUNT_NOT_FOUND outside ownership scope.
422 NOTIFICATION_LIMIT_INVALID for limit outside 1–100; malformed UUID/integer uses shared 400 error contract.
FROZEN/CLOSED account remains readable by its owner. Kafka/consumer disabled means no new notifications until enabled.

No create/delete/mark-read/admin replay API. Invalid events and exhausted processing failures go through
durable quarantine plus a sanitized Kafka DLQ; this API does not expose failed payloads or transport metadata.

See [ADR-006](../architecture/ADR-006-kafka-notifications.md) and [local messaging guide](../development/local-messaging.md).
