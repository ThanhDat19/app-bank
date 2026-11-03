# ADR-012 — Durable external workflow and conservative UNKNOWN recovery

BL-018b / issue #39; base main796fb5f after owner merged BL-018a PR #38. No pending comments.

## Local consistency boundary

ExternalPaymentService orchestrates with Propagation.NEVER. Source authorization, idempotency lookup, partner health and
risk happen in independent short reads/RPCs before preparation. ExternalPaymentStates owns local READ COMMITTED writes.
It has no partner transport dependency. HttpPartnerGateway rejects an active transaction before I/O.

Creation claims issuer/subject/key/hash, locks the source/clearing pair in the existing UUID order, validates provisioning,
policy/funds and risk freshness, and commits PREPARED with its gross reservation and risk/partner-instance snapshot.
The intent (source/clearing/beneficiary/amount/hash/actor/key/epoch/risk evidence) is immutable. Concurrent same-key
preparations return the winning command; changed intent conflicts. An error does not prove another attempt never committed.

Dispatch first locks the command with FOR NO KEY UPDATE, rechecks current accounts/policy/quote, then commits UNKNOWN.
Only a successful PREPARED claim may PUT. All other UNKNOWN attempts query. This intentionally favors safety over
availability: a process crash after claim but before I/O can retain an unresolved command forever.404 does not authorize
resubmission/release. The simulator now accepts an optional If-Partner-Instance precondition; the core always supplies it,
so a restarted epoch rejects before accepting the old intent. Same-epoch transport duplicates remain partner-idempotent.

## Reservations and settlement

V15 centralizes reserved_balance_at/reservation_count_at as STABLE snapshot readers over old unexpired holds plus
nonexpiring PREPARED/UNKNOWN external gross reservations. The existing volatile reserved_balance writer wrapper reads
after locks with the DB clock. API balance and SSE use the stable helpers in their own statement snapshots. Existing
hold placement, every ledger debit/reversal and account closure SQL guards use the combined writer function.
Both source and clearing also have a dedicated pending-command closure guard, including zero-balance clearing. The Java
closure port returns unavailable and SQL rejects closure under unresolved obligations or unsafe isolation. Reconciliation's
existing HOLDS_EXCEED_POSTED code now covers both reservation types, preserving other V12 diagnostics.

No new lock ordering is introduced between bank accounts. Every external write locks command first, then bank pair in
UUID order. FK locks are deferred; command writes use NO KEY UPDATE compatible with history's FK KEY SHARE. Other money
writers lock banks but only read the reservation sum, never lock command rows. Network writes/reads hold no DB locks.

A matching success locks the command, marks SETTLING (uncommittable, excludes only this command's reservation), then uses
the existing LedgerTransferAccess to post principal to configured synthetic clearing and assess current fees/limits/usage.
It completes SUCCEEDED with the unique reference/journal in the same transaction. Bank locks protect the temporary release;
other readers see either old reservation/old balance or committed debit/released reservation. Deferred SQL validation binds
the exact two-line POSTED principal/actor/reference/accounts/amount and outgoing charge. Outbox/audit/fee journals roll back
with any error. A second observer sees terminal state and cannot post again.

Matching failure releases without posting; cancellation only applies before a dispatch claim. Success that cannot settle
because of freeze/fee/limit/DB faults leaves UNKNOWN and its reservation. The orchestrator lets the failed transaction
roll back before saving verified evidence/problem in a separate transaction. Identical verified success can later retry
settlement. Policy usage is consumed only at settlement; preparation/dispatch checks cannot guarantee a later policy or day.
Risk approval is durable at preparation, not a new assessment every reconcile. The fee quote is not a future pricing promise.

## Evidence, history and privacy

Strict binding validates partner epoch, payment ID, normalized command hash, exact principal/currency/beneficiary and
known version/state pairs. Version1 PENDING cannot regress accepted terminal evidence; same-version conflicts preserve
funds. SUCCEEDED/FAILED/CANCELLED local states are immutable. Structural validation trusts the local partner process;
it is not cryptographic attestation or a defense against a privileged DB/OS owner.

V15 adds command + append-only history only, without changing existing money/holds/events/audit/risk snapshots. SQL
enforces allowed transitions, immutable identity/risk/quote, finalized state/history at commit, owned active preparation
and dispatch, available reservation and exact successful journal. History excludes internal atomic phases and rejects
forged future rows. Financial settlement retains V12 journal audit and V10 outbox; nonfinancial lifecycle evidence lives
in the new history, not invented historical central audit events.

Partner receives only opaque ID/hash, amount/VND and SIM_ token; no raw account IDs, actor identities, key or JWT. Only
CUSTOMER original identity reads/acts on commands/history; no operations override/forced resolution endpoint. The clearing
account stays internal to core and is fixed at preparation, not chosen by the caller or changed on replay.

## Limits and rollout

External API defaults disabled and requires local profile/explicit clearing account when enabled. Read/cancel remain safe
with transport off. Connect/read timeouts, no redirects and parsed response bounds provide local simulator limits; no
end-to-end write/read/slow-client/load/remote-authenticated transport SLO. No automatic financial retry, scheduler or query
worker, retention cleanup, cluster rate limit, provider failover, global posted-usage reservation, external reversal or
maker-checker manual resolution. Missing evidence after partner restart is deliberately held for later authorized resolution.

Deploy V15/readers together before enabling external commands; old balance readers are unaware of these reservations.
Keep the combined SQL debit/closure guards even when disabling transport. No down migration or auto-repair of pending
payments. This increment completes the local BL-018 simulator workflow for owner review, not real-bank settlement/finality.
