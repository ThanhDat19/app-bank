# ADR-011 — External partner simulator before durable core payment workflow

BL-018a / issue #37; base main4e11c57 after owner merged BL-017 PR #36. No pending review comments.

The external workflow spans independently failing commits. Implement the partner contract first as a reviewable
dependency; do not simulate atomicity between partner acceptance and the local ledger. This PR deliberately adds
no core payment endpoint, reservation, journal or migration. BL-018 remains in progress until the later core
workflow is reviewed, tested and merged.

The shared Maven build attaches a partner-simulator classifier JAR with a separate main class, outside the core's
component scan. Like risk simulator, it starts no Spring context/DB/IAM. Shared packaging is explicit; independent
service release/image hardening is not claimed. JDK HttpServer and existing Jackson dependencies suffice.

Operations bind a caller-selected UUID to an immutable canonical intent. Creation and state transitions synchronize
in memory, so concurrent same-ID calls create one operation within a process and conflicting payloads reject.
An immutable observation is captured before an optional test delay. Completion can therefore occur and be queried
before the old response arrives. Retained history is capped at two observations; terminal states are sticky.

Default mode auto-succeeds after a short timer. Explicit test mode disables the timer and allows terminal/fault
controls. Controls cannot be activated by an ordinary request; startup must opt in. Public listener is loopback only,
and beneficiary syntax restricts examples to SIM_ tokens. Parsing enforces exact fields/types/precision and bounds.

Volatile state and a fresh process instance UUID intentionally model a partner that lost its record. In-memory
deduplication cannot guarantee once-only external effects after restart. No core implementation may release funds
from404/timeout or blindly resubmit across this ambiguity. The conservative next workflow must retain UNKNOWN and
its nonexpiring reservation until definitive matching evidence/manual reconciliation. This PR demonstrates the
failure surface; it does not implement those financial safeguards or claim complete BL-018 acceptance.

The next reviewable increment must add durable issuer/subject/key command binding, risk before approval,
reservations respected by all debit/closure paths, PREPARED/UNKNOWN/terminal transitions, query reconciliation,
monotonic/instance-bound observation validation, and once-only settlement with journal/outbox/audit in one transaction.
Account state changes and ambiguous partner success must remain unresolved rather than silently undo external effects.
No external callback, polling worker or financial retry is started by this foundation.
