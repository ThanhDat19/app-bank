# ADR-008 — Immutable history và timezone-aware statements

Status: proposed for owner review. BL-015 [issue #31](https://github.com/ThanhDat19/app-bank/issues/31).
Dependency BL-014 PR #30 merged; base main `68576b1`. BL-016 SSE stays a separate task.

## Decision

Use explicit POST-created read snapshots, not a live `(max ID, posted time)` watermark. Commit order can differ
from generated IDs and booking time, and a later commit may be backdated. A REPEATABLE READ transaction copies
all eligible account-side POSTED postings into immutable V13 rows and validates completeness before commit.
Subsequent HTTP pages read those rows by sequence, so later/backdated source commits do not alter old pages.
Creating a new snapshot sees later committed data. No DB transaction or source cursor stays open across requests.

One row per posting, sorted `(posted_at,journal_id,posting_id)` ascending, including multiple postings for the
same account/journal. Sequence is assigned once. Copy preserves posting/journal/reference IDs, kind, UTC posting
time, side, amount, signed delta and running balance. No counterparty account identity, actor/JWT, reason or raw
event payload is copied. Separate fee/reversal journals remain separate entries; original postings are retained.

## Dates, cutoff and money

HISTORY copies all visible POSTED account entries, opening=0. It represents committed ledger history, including
legacy entries; it is not constrained by outbox/audit cutovers. STATEMENT selects local dates `[fromDate,toDate)`,
1–366 days, years 1–9999, with an explicit Java IANA ZoneId. Java atStartOfDay is the sole conversion authority,
including DST gaps/overlaps. Persist both date labels and derived UTC instants rather than mixing Java/PG tzdb rules.

Statement effective end is min(requested end instant, DB transaction timestamp). A future-start period is rejected;
an end in the future yields interim=true and a capped window, not a forecast. Opening balance sums POSTED entries
strictly before the local start; window entries use start-inclusive/end-exclusive instants. Empty windows preserve
opening as closing. A skipped local date can have equal UTC boundaries; a local day need not be 24 hours.
History includes all visible POSTED rows regardless of source booking timestamp; statements apply their explicit cutoff.

All amounts/totals/balances are integer VND strings, no floating-point conversion. Totals can exceed a single
posting's 20-digit limit over time. Historical opening/running/closing are signed: late backdated corrections
can create a negative booking-time prefix even when current posted balance is nonnegative. Do not clamp or hide it.
Statements describe POSTED ledger entries, not available/reserved balance or value-date settlement.

## Database and ownership

V13 stores header + entries; BUILDING cannot survive commit. Ready header/entries reject mutation/delete/truncate.
Each copy row must match an eligible source. Finalization checks full source count, contiguous/deterministic
sequence, exact opening/totals/closing and each running balance. Oversized or faulted copies roll back entirely.
Header account/ledger FKs are deferred until commit to avoid holding mutable bank-account FK locks during the scan;
immutable source-posting references are retained. A paused snapshot test proves a financial writer can commit meanwhile.

CUSTOMER ownership is checked at create/header/page/export, with snapshot bound to the path account. No OPERATOR-only
access route is added. FROZEN/CLOSED accounts remain readable; unprovisioned ledger is rejected. Account ownership
of a provisioned ledger stays under the existing immutable identity contract.

Default cap is 10000 entries/snapshot, configurable 1–100000; creation has a 30s transaction timeout. Cap violation
returns HISTORY_TOO_LARGE, never a silently truncated READY artifact. Snapshot POST is non-idempotent and only writes
read artifacts, not financial journals, money, outbox or audit events. On a lost response, another POST may create
another snapshot. No automatic retention/pruning, per-customer quota, snapshot listing or expiry is implemented.

## Export and limits

CSV contains fixed columns from immutable entry rows, UTF-8 with CRLF. Controlled UUID/enum/time/integer fields
need no user-supplied filename/text/formula handling. A UUID-based attachment filename is generated server-side;
ownership and no-store apply to exports. Opening/closing/range/zone metadata remain available through the header URI.
Export is bounded by the persisted snapshot cap and assembled before HTTP response commit, not an async auth context.
CSV bytes retain all digits; spreadsheet clients may auto-convert large numeric cells, so preserve amounts as text
when importing. No PDF, digital signature or certified bank-document claim is made.

No source repair, current-balance projection, continuous history feed, global/account stream ordering or SSE here.
Read access/snapshot creation are not added to the central V12 audit catalog, whose current scope remains journals
and reconciliation runs. No standalone JAR/production-scale export or retention policy is implied by local tests.
