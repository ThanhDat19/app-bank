# Project Charter — BL-000

- Product: Bank Platform (simulation only).
- Objective: demonstrate rigorous backend banking engineering, not claim a licensed bank.
- Scope: customer (synthetic), account, append-only ledger, balance, internal transfer, idempotency, limits, audit, reconciliation, statement, notification, simulated external payment.
- Out of scope for MVP: cards, loans, full AML/KYC vendor integration, production regulatory certification, real settlement.
- Reviewer/Product owner: repository owner (human).
- Development model: issue -> branch -> commits -> PR -> CI -> human review -> merge.
- First milestone: a bootable Spring Boot app, Swagger UI, PostgreSQL Compose, migration baseline and repository governance.
