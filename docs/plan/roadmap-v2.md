# Bank Platform — Delivery Roadmap v2 (proposal)

> Training/portfolio simulation only. Not a licensed bank or production-ready financial system.
> This document supersedes the earlier 20-sprint proposal for planning; no implementation is considered accepted until CI and human review pass.

## Delivery model
- **Cadence:** 1 week per sprint, approximately 10–15 hours/week; 2–3 focused work sessions, adjustable by reviewer.
- **Governance:** GitHub Issue -> feature branch -> small commits -> automated tests -> Swagger/OpenAPI -> Draft PR -> owner review -> merge.
- **Owner:** human reviewer approves requirements, cloud spending, secrets, release and merge. AI can author code, tests, docs, migrations, IaC and propose PRs.
- **Release gates:** no core transfer before balanced ledger + simulated funding; no event analytics before transactional outbox; no paid cloud resources before explicit cost/security approval.
- **Cloud distinction:** LocalStack emulates some AWS APIs; passing LocalStack tests is NOT proof of successful AWS deployment.

## Sprint plan

| Sprint | Main scope | Acceptance gate |
|---|---|---|
| S00 | BL-001 Bootstrap: Java 21, Spring Boot, PostgreSQL, Flyway, Swagger, CI | App starts; docs and tests pass |
| S01 | BL-040 Dockerize app, multi-stage image, Compose, health checks; API errors | `docker compose up --build`, Swagger, restart persistence |
| S02 | Customer onboarding with synthetic KYC, validations | Ownership, validation and negative tests |
| S03 | Account lifecycle, product/account states | Valid transitions, role isolation |
| S04 | Chart of accounts and immutable double-entry ledger | Per-currency journal balanced; append-only |
| S05 | Simulated cash-in and funding source | All money created via controlled offsetting journal |
| S06 | Internal transfer v1 | Atomic debit and credit in one DB transaction |
| S07 | Idempotency, locking, concurrent spending and retry | No double posting / overdraft on concurrent requests |
| S08 | Holds, available balance, limits and fees | Invariants and boundary tests |
| S09 | Keycloak/OIDC, JWT, RBAC and ownership | 401/403, token validation, security tests |
| S10 | Transactional outbox, relay and Kafka bootstrap | Crash/retry consistency, idempotent consumers |
| S11 | BL-041 Banking Analytics Service + Notification Service, Docker | Event dedup, aggregate correctness, Kafka replay |
| S12 | Reconciliation and audit | Injected imbalance and missing event detected |
| S13 | Transaction history, statements, export | Pagination, access control, amount reconciliation |
| S14 | Risk service over gRPC Unary | Deadline, deny/allow, fail-closed policy |
| S15 | Simulated external payment with state machine | UNKNOWN/retry/reversal/recovery flows |
| S16 | Operator workflows and maker-checker | Two distinct approvals, audit trail |
| S17 | Observability: Prometheus/Grafana/OpenTelemetry, performance baseline | Metrics, traces, load tests, runbooks |
| S18 | Reliability, chaos/fault injection, backup/restore | RPO/RTO simulation evidence |
| S19 | BL-042 IaC + LocalStack AWS simulation | Validated infrastructure and cleanup; limitations documented |
| S20 | BL-043 Optional real AWS sandbox deployment | Human cost approval, private DB, TLS, ECR/ECS or EC2, smoke test |
| S21 | AWS CI/CD hardening, deployment rollback, security and cost review | Manual deployment approval and teardown test |
| S22 | Portfolio v1.0 release, architecture walkthrough, demo and interviews | Approved end-to-end demo and documentation |

## Infrastructure tracks
**Local development:** Docker Desktop + Docker Compose (Spring Boot, PostgreSQL, Kafka, Keycloak, Analytics, Notification and supporting tools as needed). Use Compose profiles to avoid starting every dependency for every feature.

**Local AWS emulation:** IaC using CloudFormation or Terraform selected by ADR; LocalStack support depends on edition and service/API. Track any unsupported AWS behavior explicitly.

**Actual AWS (optional and paid):** ECR image registry; choose ECS Fargate vs EC2/Compose through a cost/security ADR; RDS or sandbox database option; CloudWatch; private networking; secrets management; HTTPS; CI/CD with manual approval. Do not default to MSK, NAT Gateway or always-on ALB without a budget estimate. Use synthetic data only.

## Cross-cutting Definition of Done
1. Reviewed business rules and acceptance tests.
2. Build/tests/DB migration pass; concurrency/fault tests for money movement.
3. Swagger/OpenAPI and versioned Kafka/gRPC contracts where applicable.
4. Security and privacy reviewed; no secrets/PII in git, events or logs.
5. Docker and deployment changes have reproducible instructions and rollback.
6. GitHub PR reviewed by owner. No AI self-approval or direct main push.

## Reference mapping
Source video: https://www.youtube.com/watch?v=tseqdcFfTUY
Source repo: https://github.com/chrisblakely01/java-spring-microservices
- Docker ~02:49–03:28
- gRPC ~03:28–04:43
- Kafka ~04:43–05:33
- Analytics service ~05:33–06:02
- Gateway/Auth ~06:02–08:40
- Integration tests ~08:45–09:23
- AWS/LocalStack/IaC ~09:23–11:37
- Docker images and LocalStack deployment ~11:37–11:58

These are instructional references, not proof that the banking system has implemented these capabilities.
