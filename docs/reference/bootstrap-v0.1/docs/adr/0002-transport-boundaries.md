# ADR-002: Transport Decisions

- **Status**: proposed.
- REST/OpenAPI: client-facing request/response and status queries.
- In-process Java call: modules inside the core consistency boundary.
- gRPC unary: synchronous communication between *distinct* internal deployables when measured benefit or contract requirements justify it.
- Kafka + transactional outbox: committed domain event distribution for notifications, analytics, reconciliation; not a distributed debit/credit transaction.
- SSE/WebSocket: future realtime client updates via a backend gateway; clients never connect directly to Kafka.
- Avoid adding Kafka, streaming or gRPC solely for the sake of a technology checklist.
