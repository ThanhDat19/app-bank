# Bank Platform — Roadmap Cloud/Analytics Addendum (draft for review)

**Status:** Draft; **does not supersede** [the authoritative roadmap](../../docs/project/roadmap.md).  
**Last audited:** 2026-10-25.  
**Scope:** Add the platform/cloud elements observed in the reference course without reordering already approved banking delivery.

## Why this addendum exists

An earlier draft claimed Analytics and AWS were the only missing pieces and re-numbered the first sprints. Both claims were incorrect:
- The course also demonstrates separate Dockerfiles, gateway, JWT auth, gRPC, Kafka/Protobuf, integration tests, Java AWS CDK synthesizing CloudFormation, LocalStack, VPC, ECS/Fargate, RDS, MSK, ALB, CloudWatch log groups and related networking.
- The main project has **already merged BL-001, BL-002 and BL-003**, while **BL-004 has an open PR**. Replacing S01 with Docker and S02 with Customer would contradict the approved project history.
- The reference Analytics consumer only logs Kafka messages; it does not implement a real analytics read model.
- The reference repository contains Dockerfiles but no root Docker Compose manifest; its README mentions Notification Service, but no notification-service directory is present in the verified tree.
- Cloud deployment in the tutorial uses AWS CDK Java -> synthesized CloudFormation -> LocalStack. This is not evidence of a tested production AWS deployment.

See [the audit matrix](../audit/reference-parity-and-banking-gaps.md) before merging.

## Preserve existing core roadmap and delivery order

The authoritative [20-sprint plan](../../docs/project/roadmap.md) retains its BL-001..BL-023 IDs, gates and business priorities. Existing statuses must be read from GitHub Issues/PRs, not inferred from calendar dates.

| Existing milestone | Core work | Additional platform work (separate issues) |
|---|---|---|
| BL-001..BL-003 | Bootstrap, API conventions, Keycloak OIDC/RBAC — merged | Do not re-plan or redo completed work |
| BL-004 onward | Customer/KYC, account, ledger, cash-in, transfer, idempotency, reversal | **#9 / BL-040:** Docker image and Compose enhancements can be reviewed independently, after ensuring compatibility with current Compose |
| BL-012 / Outbox; BL-013 / Kafka | Reliable committed financial events, notification | **#10 / BL-041:** Analytics Service follows Outbox/Kafka and needs its own DB, replay/dedupe tests, API and Dockerfile |
| BL-014..BL-023 | Reconciliation, statements, risk, external simulation, operations, metrics, recovery, portfolio | **#11 / BL-042:** IaC + LocalStack (AWS CDK Java first, with ADR if switching); cloud infrastructure tests and teardown |
| After explicit budget/security review | No financial correctness change | **#12 / BL-043:** Optional actual AWS sandbox (synthetic data), manual CD approval, cost alarms, TLS, private DB, logs, rollback and teardown |

**Scheduling:** BL-040 can run as an infrastructure track without blocking BL-004. BL-041 must not be started before the core outbox/event contract. BL-042 can start with IaC design earlier but end-to-end tests require deployable services. BL-043 is opt-in and never automatically triggered by a merge.

## Technology and operational requirements

1. **Local Docker:** multi-stage JDK 21 image, non-root runtime, healthchecks, Compose profiles for core/IAM/messaging/analytics/observability, named DB volumes, localhost-only ports, no committed secrets.
2. **API Gateway:** required only once multiple services justify a gateway; TLS termination, auth boundary, rate limiting, correlation and documented routes. Do not mistake gateway JWT validation for authorization of account ownership.
3. **gRPC:** typed internal service-to-service unary RPC where a separate risk service is justified, with deadlines and error policy; no remote RPC inside the locked ledger transaction.
4. **Kafka:** versioned Protobuf/JSON event contracts, outbox relay, idempotent consumers, retries/DLQ, partition ordering and replay. Exactly-once Kafka processing does not imply exactly-once external DB writes.
5. **Analytics:** own read model, dedupe, aggregated transfer metrics, replay and access-controlled REST/OpenAPI; never change authoritative ledger or perform synchronous fraud authorization.
6. **AWS IaC:** course uses **Java AWS CDK**, synthesizing CloudFormation; LocalStack emulation support varies by edition. Evaluate VPC, ECS/Fargate, RDS, MSK, ALB, logs, DNS/healthchecks, IAM, ECR, secrets and AWS budgets individually.
7. **CI/CD:** automated tests, integration/contract checks, image scanning, build/push to registry, deploy with human approval, smoke tests, rollback, drift detection and environment separation.
8. **Observability:** structured/redacted logs, OpenTelemetry tracing, Micrometer/Prometheus/Grafana locally, CloudWatch in AWS, health/readiness, consumer lag, outbox backlog, alerts and SLO evidence.
9. **Security:** TLS, OIDC/RBAC + resource ownership, least-privilege AWS IAM, secret rotation, dependency/SAST checks, no PII in Kafka/logs, backup/restore and disaster recovery.
10. **Financial invariants:** double-entry balanced journal, immutable posting, atomic transfer, idempotency and concurrency testing are more important than technology parity with a patient-management tutorial.

## Issue and PR workflow
- [#9 Docker](https://github.com/ThanhDat19/app-bank/issues/9)
- [#10 Analytics](https://github.com/ThanhDat19/app-bank/issues/10)
- [#11 AWS CDK/LocalStack](https://github.com/ThanhDat19/app-bank/issues/11)
- [#12 Optional AWS sandbox](https://github.com/ThanhDat19/app-bank/issues/12)

**Approval required:** human owner reviews architecture, financial correctness, PR diff, AWS budget and every cloud deployment. This document is planning only; no cloud resources are created.
