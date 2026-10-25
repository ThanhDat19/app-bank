# Delivery Roadmap — proposal (20 weekly sprints)

Cadence assumption: solo developer + AI assistance, ~10–15 hours per week; dates and velocity to be approved by owner. One sprint can span multiple chat sessions and PRs.

| Sprint | Goal | Candidate issues | Acceptance gate |
|---|---|---|---|
| S00 (week 1) | Repo baseline + local environment | BL-001/002 | Spring starts, Swagger + PostgreSQL + CI work |
| S01 (week 2) | Domain contracts and OpenAPI baseline | BL-003/004 | Reviewed rules, schemas, standardized errors |
| S02 (week 3) | Synthetic customer onboarding | BL-005/006 | CRUD / validation / ownership tests |
| S03 (week 4) | Account lifecycle | BL-007/008 | Valid state transition and permissions |
| S04 (week 5) | Chart of accounts + Ledger | BL-009/010 | Balanced and immutable journals |
| S05 (week 6) | Simulated cash-in | BL-011 | Journal-backed funding only |
| S06 (week 7) | Internal transfer v1 | BL-012/013 | Atomic debit-credit |
| S07 (week 8) | Idempotency + concurrency | BL-014/015 | Duplicate/overspend tests pass |
| S08 (week 9) | Holds, limits, fees | BL-016/017 | Available balance and limits enforce rules |
| S09 (week 10) | OIDC + resource authorization | BL-018/019 | 401/403/owner isolation tests |
| S10 (week 11) | Outbox + reliable event relay | BL-020/021 | Crash before/after commit tested |
| S11 (week 12) | Kafka + notification | BL-022/023 | Consumer de-dup and replay |
| S12 (week 13) | Reconciliation + audit | BL-024/025 | Deliberate inconsistencies detected |
| S13 (week 14) | Transaction history / statements | BL-026/027 | Pagination and account-scope queries |
| S14 (week 15) | External Risk microservice | BL-028/029 | gRPC with deadlines/fail-closed rules |
| S15 (week 16) | Simulated external payment rail | BL-030/031 | Explicit UNKNOWN and recovery states |
| S16 (week 17) | Operator workflow / maker-checker | BL-032/033 | Two distinct approvals enforced |
| S17 (week 18) | Observability + load test | BL-034/035 | Metrics, trace, benchmark report |
| S18 (week 19) | Failure injection / restore rehearsal | BL-036/037 | Runbooks verified |
| S19 (week 20) | Portfolio release / interview demos | BL-038/039 | End-to-end demo and architecture package |

**Scope gate**: the first real financial transfer PR cannot start before Ledger + funding is approved. No fake balance increments. Other modules may be independently scheduled after owner review.
