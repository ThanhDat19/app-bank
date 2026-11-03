# BL-016 — Account SSE

`GET /api/v1/accounts/{accountId}/events`, `Accept: text/event-stream`, `Authorization: Bearer <access token>`.
CUSTOMER ownership is issuer/subject scoped; OPERATOR-only 403, another account or missing account 404.
Frozen, closed and unprovisioned accounts can read. UUID syntax errors are 400. Missing/invalid token is 401;
this endpoint additionally rejects exp<=now even inside the decoder's general clock-skew allowance.
409 `SSE_CONNECTION_LIMIT` means a per-process or per-identity limit; back off before retrying.
Error handshakes use the existing sanitized Problem Details/correlation contract. After SSE begins,
authorization/query/transport failures close the channel without an error payload. Reconnect reauthenticates.

Responses: `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`, correlation ID.

```text
event:resync
retry:5000
data:{"accountId":"<owned UUID>","reason":"CONNECTED"}

:keepalive

event:resync
retry:5000
data:{"accountId":"<owned UUID>","reason":"STATE_CHANGED"}

```

The only reasons are CONNECTED and STATE_CHANGED. No amount, balance, journal, identity, profile, reason
text or raw event is included. SSE messages have no id. **Last-Event-ID is ignored**, including stale/unknown
values; it grants neither replay nor authority. One connection always starts with CONNECTED.
Read-only committed sampling detects lifecycle/provisioning, posting count, active reservations/expiry and
notification count. Changes coalesce. No per-event completeness, ordering or latency SLA is promised.

## Required client recovery

1. Connect with a currently valid token using fetch streaming. Never put tokens in a URL. Native EventSource
   does not support this Authorization-header contract; this task adds no browser UI or auth workaround.
2. On CONNECTED and STATE_CHANGED, coalesce refresh work and GET `/api/v1/accounts/{accountId}`,
   `/api/v1/ledger/accounts/{accountId}/balance` when provisioned, and `/api/v1/accounts/{accountId}/notifications`.
   GET known `/api/v1/transfers/{id}`, `/api/v1/holds/{id}` or `/api/v1/external-payments/{id}` for command-specific
   status under their ownership rules. External reserve/release/settlement changes can invalidate the sample;
   not every UNKNOWN problem/version change creates a hint. Keep periodic command queries. Notification lists
   remain bounded, eventual lists; SSE adds no full event-history guarantee.
3. If a command response was lost and no receipt ID is known, replay the **same request and original
   Idempotency-Key**. Never issue a new financial command/key based on a missing SSE hint.
4. If an existing history/statement snapshot is shown, it remains immutable. A fresh read requires POSTing
   a new snapshot under the BL-015 contract; do not automatically create retained snapshots every heartbeat.
5. Heartbeat comments only keep the transport alive. Keep periodic REST refresh as a fallback. On EOF/error,
   wait at least the server's 5000ms retry guidance plus jitter, refresh token if necessary and reconnect;
   always resynchronize again. On 401 stop reconnecting until authentication is refreshed, on 403/404 stop
   and surface access failure, on 409 back off. Do not run parallel duplicate reconnect loops.

Default lifetime is 120s and earlier at token exp. A fresh connection can use another valid token, but the
server does not refresh tokens or check online revocation. A frame already being written can arrive after exp.
No ordering across REST calls is implied; each REST resource retains its existing transaction semantics.
