# BL-000 — Course parity, platform gaps and banking readiness audit

**Status:** Proposed for owner review, 2026-10-25.  
**Reference:** [Chris Blakely Java Spring Microservices](https://github.com/chrisblakely01/java-spring-microservices), inspected repository tree and selected source files.  
**Target:** [ThanhDat19/app-bank](https://github.com/ThanhDat19/app-bank), `main` and currently open PRs.  
**Audit limitation:** This is a targeted repository/design audit, **not** a runtime test, full security assessment or proof of AWS deployment. Feature status refers to observed repository files and PR/issue state, not an undocumented environment.

## Executive assessment

It was incorrect to state that only Analytics and AWS were missing. The reference course contains a broader platform and deployment track; the bank requires stronger financial, security and reliability controls than the tutorial demonstrates. Moreover, the first proposed '23 sprint' replacement conflicted with already merged BL-001/002/003 and the open BL-004 PR. The approved 20-sprint core roadmap must remain the scheduling baseline until owner approval.

## A. Reference course coverage vs Bank Platform

| Topic | Evidence in reference | Current Bank Platform / roadmap | Action |
|---|---|---|---|
| Docker packaging | Dockerfile per service | Existing Compose and local runtime; application image not yet the approved deployment baseline | BL-040 multi-stage image, healthchecks, Compose profiles |
| Docker Compose | No root Compose file in inspected reference tree | Existing `compose.yaml` for PostgreSQL/Keycloak | Keep existing; add app, messaging and analytics incrementally |
| REST/DTO/validation | Patient Controller, DTO, exception handler | BL-001/002 completed, business APIs later | Keep OpenAPI and error contracts for each feature |
| Swagger/OpenAPI | Gateway API docs routing and springdoc | Already configured in target | Keep as Definition of Done |
| Auth/JWT/API Gateway | Auth Service and JWT Gateway Filter | BL-003 Keycloak OIDC/JWT completed; gateway not yet needed for monolith | Introduce gateway only with justified service split; ownership still enforced in core |
| gRPC + Protobuf | BillingGrpcService, proto and client | Planned Risk Service | Implement with deadlines, contracts and tests |
| Kafka + Protobuf | Patient KafkaProducer and patient_event.proto | Planned Outbox/Kafka | Add durable relay, versioning, consumer dedupe |
| Analytics | `analytics-service/KafkaConsumer.java` parses event and **logs it only** | Not implemented | Build real aggregation read model and access-controlled APIs (BL-041) |
| Notification | README mentions service; **no notification-service directory in inspected tree** | Planned in core roadmap | Implement separately from reference, with retry/dedupe |
| Integration tests | RestAssured external HTTP tests | Target already has Testcontainers integration tests; domain E2E later | Keep and extend concurrency, failure and contract tests |
| AWS IaC | `infrastructure/src/main/java/com/pm/stack/LocalStack.java`: **Java AWS CDK** | Not implemented | Prefer CDK Java to mirror course; CDK synthesizes CloudFormation |
| LocalStack | `infrastructure/localstack-deploy.sh` deploys generated CFN template | Not implemented | Validate selected edition/API coverage, cleanup, smoke tests |
| AWS networking | VPC, ECS cluster, Cloud Map | Not implemented | IaC, private network, least privilege, cost analysis |
| AWS workloads | ECS Fargate, ALB | Not implemented | Compare Fargate vs EC2 sandbox; manual deployment |
| AWS data/event infra | RDS PostgreSQL, MSK Kafka | Not implemented | Avoid costly defaults; separate local/cloud architectures |
| AWS logging | CloudWatch log groups, one-day retention in reference | Planned monitoring | Structured redacted logs, retention and alarms |
| Image registry / delivery | Source CDK uses `ContainerImage.fromRegistry(imageName)`; no verified full ECR CI/CD flow in inspected tree | Not implemented | Explicit ECR build/push, image tagging, CD approval, rollback |
| Observability | Basic log groups and health checks in IaC | Target has Actuator health; metrics/tracing planned | OpenTelemetry, metrics, dashboards, alerts and SLO |
| Security/operations | Reference demo credentials/config and short log retention | Keycloak started, bank-grade hardening incomplete | Secret rotation, no PII logs, threat model, audit, backup, incident response |

## B. Source-code findings that must NOT be copied into banking

1. **Analytics is a stub.** [KafkaConsumer.java](https://github.com/chrisblakely01/java-spring-microservices/blob/main/analytics-service/src/main/java/com/pm/analyticsservice/kafka/KafkaConsumer.java) contains a placeholder comment and logs PatientId, PatientName and PatientEmail. There is no shown aggregate read model, database persistence, dedupe or analytics API.
2. **Kafka producer lacks a transactional outbox.** [KafkaProducer.java](https://github.com/chrisblakely01/java-spring-microservices/blob/main/patient-service/src/main/java/com/pm/patientservice/kafka/KafkaProducer.java) sends an event without awaiting asynchronous delivery and catches only synchronous exceptions. Financial events need outbox, delivery observability, retries and consumer dedupe.
3. **AWS infrastructure is demo IaC, not a production reference.** [LocalStack.java](https://github.com/chrisblakely01/java-spring-microservices/blob/main/infrastructure/src/main/java/com/pm/stack/LocalStack.java) uses AWS CDK Java; it includes an inline JWT secret environment value, `RemovalPolicy.DESTROY` for RDS/log groups, and MSK `kafka.m5.xlarge` (one broker). These are inappropriate defaults for real banking and potentially costly on AWS.
4. **CloudFormation is synthesized by CDK.** [localstack-deploy.sh](https://github.com/chrisblakely01/java-spring-microservices/blob/main/infrastructure/localstack-deploy.sh) runs `aws --endpoint-url=http://localhost:4566 cloudformation deploy` against LocalStack. This is not proof of live AWS deployment.
5. **Integration tests are useful but limited.** [PatientIntegrationTest.java](https://github.com/chrisblakely01/java-spring-microservices/blob/main/integration-tests/src/test/java/PatientIntegrationTest.java) invokes a running local gateway via RestAssured. It does not prove concurrent financial posting, broker recovery, outbox atomicity or security boundaries.
6. **README vs source mismatch.** [README.md](https://github.com/chrisblakely01/java-spring-microservices/blob/main/README.md) has a 'Notification Service' section, but no `notification-service` directory was found in the repository's recursive tree. Do not infer complete implementation from headings.

## C. Banking requirements not supplied by the patient-management course

| Criticality | Banking requirement | Gate |
|---|---|---|
| Blocker | Chart of accounts, immutable double-entry journals, per-currency balancing | Before money movement |
| Blocker | Atomic transfer + ordered locks + no overspending under concurrency | Before internal transfer acceptance |
| Blocker | Financial command idempotency, retry after lost response, durable status lookup | Before cash-in/transfer acceptance |
| Blocker | Posted/available balance, holds and reversal policy | Before advanced payment flows |
| Blocker | Ownership, KYC simulation and account state authorization | Before account write APIs |
| High | Outbox/consumer dedupe, reconciliation, repair workflow | Before analytics/external payment |
| High | Fraud/risk synchronous decisions, step-up auth policy | Before risk-sensitive operations |
| High | Statements, audit trail, maker-checker and operational controls | Before portfolio v1.0 |
| High | Backup/restore, load/concurrency tests, alerting, incident runbooks | Before AWS sandbox release |
| High | AWS least privilege, TLS, secrets, no public DB/Kafka, cost guardrails | Before paid cloud deployment |

## D. Current project status and corrected sequencing

- **Merged:** BL-001 (bootstrap), BL-002 (API conventions/module boundaries), BL-003 (Keycloak OIDC/JWT).
- **In review:** BL-004 Customer/KYC, per open PR.
- **New platform issues:** #9 Docker, #10 Analytics, #11 LocalStack IaC, #12 AWS sandbox.
- **Not yet implemented:** Financial core/ledger/transfer, Kafka/outbox, analytics, cloud deployment.
- **Authoritative roadmap:** [docs/project/roadmap.md](https://github.com/ThanhDat19/app-bank/blob/main/docs/project/roadmap.md), not a new 23-sprint replacement.

### Recommended gates
1. Complete BL-004 and core financial invariants first; BL-040 Docker can progress in parallel.
2. Implement Outbox + Kafka **before** BL-041 Analytics; do not let analytics mutate ledger.
3. Add observability, CI image delivery, security and rollback before real cloud deployment.
4. BL-042 LocalStack is a training gate; BL-043 actual AWS sandbox requires **separate human approval and cost estimate**.
5. Keep AWS CDK Java vs Terraform and ECS vs EC2 as explicit ADR decisions; course parity alone is not sufficient justification.

## Owner decisions requested
- Approve this revised gap matrix and keeping the original core roadmap as source of truth?
- Approve Docker track parallel to BL-004?
- Use AWS CDK Java (matches course) as default IaC, unless a later ADR favors Terraform?
- Confirm Analytics is asynchronous and read-only relative to financial core?
- Defer real AWS spending until explicit approval and a budget estimate?
