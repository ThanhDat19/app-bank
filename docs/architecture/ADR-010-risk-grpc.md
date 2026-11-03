# ADR-010 — Unary simulated risk before transfer posting

BL-017 / issue #35, from main `2673359` after owner merged BL-016 PR #34.

## Boundary and decision

Risk is a separate executable process with a versioned Protobuf unary RPC. Account/Ledger/Transfer remain
one PostgreSQL consistency boundary. TransferService orchestrates outside a transaction (`Propagation.NEVER`):
validate → read existing key/hash receipt → authorize source ownership → risk RPC → TransferPostingService.
The writer claims the original issuer/subject/key under its short READ COMMITTED transaction, rechecks hash,
then performs the existing ordered account locks, active/provisioning/funds/fee/daily-limit checks and posts.
Risk never calls posting, changes money, or supplies account state as authority.

Already POSTED same-key/payload receipts return before any remote call, even after freeze/closure or risk outage.
Concurrent approved attempts can perform several pure RPC evaluations but the unique claim commits money once;
losers return the winner's original financial and risk receipts. A failed preflight rechecks for a concurrent
winner before returning its error. Another attempt could still commit immediately after that recheck: an error
does not prove the key was never posted. Recover with the original key/payload or known receipt, never a new key.
Denied/failed preflight has no durable receipt/decision and does not consume the key; a retry can be assessed anew.

## Protocol, local simulator and failure policy

`src/main/proto/risk/v1/risk.proto` defines AssessTransfer. Request fields are SHA-256 request_id,
canonical positive 1..20 digit integer VND principal amount, and VND currency. Request_id binds verified
issuer/subject, idempotency key and normalized transfer hash; it is an opaque pseudonymous binding, not a
proof of anonymity. No raw actor, account/counterparty identifier, token, request reason or profile is sent.
The simulator returns the same binding, APPROVE/REJECT, a bounded policy version and a fixed reason.
Core rejects mismatched IDs, unknown outcomes/reasons or malformed policy versions.

The deterministic rule approves principal<=configured cap, default100000000 VND. It excludes fee/gross usage;
those remain the ledger's separate outgoing policy. Policy version includes the cap, e.g. amount-cap-v1-100000000.
No statistical fraud, sanctions, AML, beneficiary reputation, scoring or certified risk decision is claimed.

Core risk is disabled by default, explicitly recorded as BYPASSED/disabled-v1/RISK_DISABLED. Enabling the
plaintext simulator requires `local` profile and literal127.0.0.1; a nonlocal host/profile fails startup.
The simulator binds only loopback, has no DB/IAM setup or public REST endpoint and never executes finance.
Local OS/process trust is the security boundary. Authenticated TLS/mTLS for remote deployment is a separate
design; this task provides no insecure remote fallback. Do not expose the loopback service through a proxy.

The blocking stub has an explicit deadline (default750ms, accepted50..5000ms), bounded message size4096,
no application retries/hedging and disabled configured gRPC retry. Timeout→503 RISK_TIMEOUT; unavailable,
transport/status errors→503 RISK_UNAVAILABLE; invalid envelope→503 RISK_RESPONSE_INVALID. Return Retry-After:1
and sanitized Problem Details, without copying remote descriptions. Rejection→422 RISK_REJECTED.
Deadline/cancellation can discard a late response; no asynchronous success callback has access to posting.

Approval freshness defaults5000ms (accepted100..30000ms), measured from valid response receipt on the core
clock. It is checked before posting and again after waiting for account locks and completing locked ledger
validation. Expiry throws409 RISK_APPROVAL_EXPIRED and rolls back journal, fee usage, outbox, audit, claim and
risk record. This is freshness at the local posting validation point, not an externally synchronized decision
timestamp or guaranteed commit deadline. No remote call or sleep occurs inside that financial transaction.

## Durable evidence and upgrade

V14 adds immutable transfer_risk_decisions. One row binds transfer_id/request_hash and the validated request_id,
outcome, policy version, reason and assessed/expiry times. It commits after completing the matching transfer,
inside the same transaction. SQL insert validation requires the exact posted transfer/hash; deferred completeness
requires the risk policy row for every new transfer. Update/delete/truncate are rejected.

An immutable transfer_risk_legacy inventory records only IDs present at migration time. Inventory capture and
trigger installation run under one explicit transfer-table lock, so concurrent writers cannot
slip through the cutover. Old application processes without the new policy writer must be upgraded with V14;
their new transfers fail the completeness guard after cutover rather than silently bypassing policy.
Existing receipts, money, events and audits remain unchanged; they are not relabeled APPROVED/BYPASSED. A legacy transfer's risk
lookup returns404. New rows cannot be added to that inventory to bypass cutover. Missing/mismatched policy rows
cannot commit. SQL bindings ensure internal consistency; they do not prove authenticity of a remote approval,
and a privileged DB owner is still outside the application trust boundary.

Scope is POST /transfers only. Hold capture, reversal and cash-in keep their existing contracts. BL-018 external
payments will require their own intent/state/approval mapping; no pending/UNKNOWN external payment is added here.
Successful receipts are retained indefinitely; failed risk evaluations are not a durable security/audit ledger.
No automatic retry worker, circuit breaker, service discovery, global admission/rate limit or production SLO.

## Build and operational limits

Shared repository/build produces two executable JARs: core and risk-simulator classifier. Generated Java bindings
live in target/generated-sources and are rebuilt from the committed proto; they are not hand-edited/committed.
gRPC1.84.1 and Protobuf4.36.2 align with the existing Boot dependency baseline; OS-aware protoc generation supports
the local Windows build and Linux CI. The secondary JAR shares packaged dependencies; separate minimal images,
independent release versioning and remote transport hardening are outside this simulator increment.

Simulator executor has4 workers and64 queued tasks; inbound messages4096 and16 concurrent calls/connection.
It checks cancellation before its small pure calculation. There is no external work to retry or compensate.
Shutdown stops server/workers; core closes its channel with the application context. This is not a load or DoS
benchmark; clients fail closed under service/resource failure.

References: [gRPC Java1.84.1 codegen/dependencies](https://github.com/grpc/grpc-java/blob/v1.84.1/README.md),
[deadlines](https://grpc.io/docs/guides/deadlines/), [cancellation](https://grpc.io/docs/guides/cancellation/).
