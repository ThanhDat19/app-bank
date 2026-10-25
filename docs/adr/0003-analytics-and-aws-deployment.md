# ADR-003 (Proposed): Banking Analytics and Cloud Deployment

**Status:** Proposed — owner review required.\n\n**Course-parity correction (2026-10-25):** The reference infrastructure is written in **AWS CDK for Java**, which synthesizes CloudFormation templates deployed to **LocalStack**. This is not Terraform and is not evidence of a verified live AWS deployment. The reference analytics consumer is a logging stub, not a complete analytics pipeline. See [the audit](../audit/reference-parity-and-banking-gaps.md).  
**System:** Bank Platform simulation (synthetic data only).  
**Reference:** Chris Blakely's Java/Spring Microservices tutorial, specifically analytics consumer and LocalStack deployment.

## 1. Analytics is not a Docker feature
Docker packages/runs services. `analytics-service` is a distinct Spring Boot application with a Kafka consumer and its own read model. Its responsibilities are aggregated reporting and monitoring, not financial posting or pre-transfer authorization.

### Flow
```text
REST Client -> API Gateway -> Core Banking -> PostgreSQL transaction:
                                          journal + transfer + outbox
                                      |
                                  Outbox Relay
                                      |
                                  Kafka topic
                                      |
                   +------------------+------------------+
                   |                                     |
           analytics-service                    notification-service
           own reporting DB                      delivery/retry store
```

### Contracts
- Event example: `bank.transfer.posted.v1` with `event_id`, `occurred_at`, `transfer_id`, `amount_minor`, `currency`, `status`, `trace_id`; **no name, email, account number or credential**.
- Consumer stores `event_id` as a unique processed-event key, then updates aggregates transactionally.
- Processing is **at least once**; duplicate/replay and ordering must be handled explicitly. Kafka does not make external database side effects exactly once automatically.
- Read-model examples: `daily_transfer_summary`, `transfer_status_summary`, `consumer_checkpoint`.
- Example API: `GET /api/v1/analytics/transfers/daily?from=...&to=...` (operator role only), documented in OpenAPI.
- Analytics service outage must not affect posting and must not prevent completed ledger transactions.

### Testing
Duplicate event -> one aggregate update; out-of-order and malformed events; replay/backfill; dead-letter queue and poison messages; consumer restart; failure between read-model update and offset commit; reconciliation of analytics totals against authoritative ledger.

## 2. Docker development environments
- One `Dockerfile` per deployable application; multi-stage Java 21 image, non-root user, minimal runtime.
- Compose profiles: `core`, `messaging`, `analytics`, `observability`, to avoid unnecessary resource use.
- PostgreSQL volume persisted; database and Kafka not bound to public interfaces; local-only exposed ports.
- Config and credentials via local ignored `.env`; real secrets stored in AWS-managed facilities.
- CI runs tests, image build and vulnerability scanning before image promotion.

## 3. Cloud stages and fidelity
**Stage A — Docker Compose:** Validate local end-to-end service interaction; no AWS dependency.

**Stage B — LocalStack + IaC:** Practice AWS CLI, CloudFormation/Terraform, VPC and container resource definitions. Confirm selected LocalStack edition supports each API before treating a service as emulated. Track fidelity gaps, and destroy stacks after testing.

**Stage C — AWS sandbox (human-approved, paid):** Deploy only synthetic data; use ECR for images, ECS/Fargate or EC2 chosen by ADR, private DB, IAM least privilege, TLS ingress, CloudWatch, budget alarms and manual release approval. If Kafka/MSK or RDS is expensive, document a lower-cost sandbox alternative; do not silently equate it with production topology.

## 4. Security and cost gates
- Never deploy or connect to real financial networks, real customer records or money.
- No secrets in repository, image, logs or Kafka payloads.
- No public PostgreSQL/Kafka or unprotected Swagger/Admin endpoints.
- Least-privilege roles, TLS, private subnet/security groups, audit logs and teardown plan.
- Owner approves AWS account, expected monthly spend, region, credentials/roles, and deployment action before provisioning.
- Record actual deployed resources and observed tests; do not claim production readiness.

## 5. Review decisions required
1. Keep analytics asynchronous, strictly read-only toward the ledger?
2. Approve Docker Compose profiles and Dockerized services?
3. Approve AWS CDK Java as the default IaC (course parity), or request a separate ADR for Terraform?
4. Choose AWS ECS Fargate or low-cost EC2 sandbox after price comparison?
5. Approve cloud budget and manual deploy gate?

**No real AWS deployment is authorized by this ADR.**
