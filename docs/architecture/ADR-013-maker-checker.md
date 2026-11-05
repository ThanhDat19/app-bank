# ADR-013 — Mandatory operations approval in the execution transaction

Status: implemented for BL-019, owner review pending. Issue #41; parent PR #40 merged.

Previously one OPERATOR could freeze/unfreeze or reverse a public journal directly. Separate maker/checker requests
now authorize a fixed intent, with approval and the existing effect in one READ COMMITTED transaction.
See [API/rules/cutover](../api/maker-checker.md).

## Decision

`operations` owns durable request, queue/history, approval orchestration and the JDBC implementation of the shared
`OperationApprovalGuard` port. Account exposes `AccountOperationAccess` application contract; operations does not import
another module's domain, web DTO or repository. Account/reversal depend only on the shared guard, avoiding module cycles.
Existing ledger/reversal/account services keep the financial rules, account locks and posting/audit/outbox behavior.

Prepare validates a typed immutable intent, maker ownership for account controls and maker-scoped idempotency, without
executing it. Reversal target eligibility is authoritative at approval; pending requests may refer to a missing or
unsupported original and can be rejected/cancelled. No money or account-state guarantee is promised at prepare.

Approve acquires request FOR NO KEY UPDATE, checks maker/checker identity and decision version, validates checker account
ownership, then writes EXECUTING with the current PostgreSQL transaction ID and checker identity. Guard queries match
that transaction/type/target/version/reason/checker. Account/reversal services cannot run directly through old HTTP APIs
or a separately authenticated application call. Reversal uses operation UUID as internal idempotency key to avoid collision
between different makers' client keys when a checker approves many requests.

Lock order is request → existing reversal claim/original NO KEY UPDATE → ordered bank/ledger locks. Account controls take
request → account lock. FK KEY SHARE is compatible with NO KEY UPDATE on request/original. No network RPC under these
locks. Existing financial contexts have no dependency back on operations. Each public command is one transaction;
combining multiple effects in an outer batch transaction is not supported.

On success, a JSON receipt snapshot and APPROVED/version1 are stored with the effect. Database deferred validation
rejects EXECUTING at commit and requires an exact linked posted reversal or account history/result, matching actor/time,
and central audit for history. Failed execution or commit rolls all changes back, leaving the separately committed
PENDING intent. This includes failed central audit, account history, reversal receipt or outbox/posting writes.

## Database guards and history

V16 adds request/history tables, a bounded-queue index and unique active execution transaction. Intent and terminal
states are immutable, only approved transitions are permitted, and maker/checker must differ. Account owner checks
apply at SQL request mutation as well as service preflight. Raw account ACTIVE↔FROZEN changes require a matching
EXECUTING claim in that transaction; account history and new reversal inserts receive a validated unique operation link.
SQL cannot commit a fake approval merely by constructing a JSON result or an incomplete execution claim.

Operation history is append-only at versions0/1, automatically inserted only for PENDING and terminal decisions, with
verified maker/checker identities retained privately. It emits OPERATION_REQUESTED/APPROVED/REJECTED/CANCELLED into
existing central audit, preserving all prior journal/reconciliation audit validation. Account lifecycle history also
becomes append-only. V16 adds no money, modifies no old receipts and invents no historical approval identities.

All operators may browse latest requests/results; customers are denied. Public request/history DTOs omit raw actors,
keys and hashes. Result snapshots contain existing account metadata/reversal receipt fields and remain stable after
subsequent domain changes. Only the recorded checker can replay APPROVED with version0/1; another checker gets conflict.
Reject/cancel are terminal decisions, maker cannot reject/approve, and another operator cannot cancel.

## Consequences and limits

Direct mutations are a deliberate breaking API change with fail-closed legacy routes. No rollout flag re-enables bypass.
Schema, app and client rollout must be coordinated; an old app against V16 fails instead of mutating without approval.
Legacy GET reversal receipts stay accessible to their original actor. Pending reversal intents do not reserve money:
funds/holds/limits/account versions can change, so approval may fail and remain PENDING for retry or cancellation.

Database constraints do not authenticate JWTs; HTTP/method security establish actors, while SQL enforces stored
identity separation, linkage and completeness. Database owners/superusers and IAM administrators remain trusted.
Distinct identities do not prove distinct humans. MFA, teams/threshold policies, cash-in/KYC approval, forced external
UNKNOWN resolution, distributed workers, expiration, audit export/retention and real-bank operational certification
are outside this change.

## Validation

OperationsIT uses real signed JWT/JWKS, HTTP and PostgreSQL, with concurrency and controlled SQL/commit faults.
OperationsMigrationIT upgrades populated V15 with legacy freeze/unfreeze history, full reversal, journals/postings,
outbox/audit/risk/holds and a durable UNKNOWN external reservation; existing business JSON inventories/balances remain.
Financial regression fixtures execute real maker/checker HTTP calls through a shared test client; no approval bypass is
enabled for tests. Dedicated operations tests send raw HTTP to prove legacy routes fail closed. ArchUnit still checks
module ownership and cycles. Full evidence and limits are recorded in [BL-019 review](../reviews/BL-019.md).
