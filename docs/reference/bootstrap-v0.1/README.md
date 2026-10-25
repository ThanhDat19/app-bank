# Bank Platform (Simulation)

> **Training/portfolio environment only.** No real banking service, customer data, real money, banking network or regulatory certification.

This project is a Java 21 / Spring Boot sandbox to study banking domain design, correctness, testing and delivery practices. It is **not production-ready**.

## Delivery policy

- Work starts from a GitHub issue and a small feature branch.
- Open a PR with business rules, code, tests, API docs and evidence.
- The project owner reviews every PR and authorizes merge.
- No money-moving code is merged without tests for ledger balance, idempotency, concurrent access and failure handling.

See `docs/plan/roadmap.md`, `docs/adr/0001-ledger-consistency.md` and `docs/review/definition-of-done.md`.
