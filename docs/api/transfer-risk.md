# BL-017 — Transfer risk policy

Existing POST `/api/v1/transfers` request and Idempotency-Key scopes are unchanged. A new transfer optionally
passes a local unary risk preflight before entering the financial transaction. Source ownership is authorized
before any RPC. All final ownership/state/provisioning/funds/fee/limit checks remain under the existing locks.
The simulated threshold applies to principal amount, not fee/gross total. Risk is disabled by default; each
new successful transfer explicitly retains a BYPASSED decision. Already committed replay never needs a new RPC.

| Result | HTTP / code | Recovery |
| --- | --- | --- |
| Approved + financial checks pass | 201 original POSTED receipt | Keep Location/receipt ID and original key |
| Simulated rule rejects | 422 RISK_REJECTED | No new receipt from this attempt; a later same-key attempt may be reassessed |
| Deadline | 503 RISK_TIMEOUT | Retry-After:1; back off and use the same key/payload |
| Service/transport failure | 503 RISK_UNAVAILABLE | Same-key recovery; no automatic financial retry |
| Invalid response/binding/policy | 503 RISK_RESPONSE_INVALID | Fail closed; operator investigates service contract |
| Approval stale at final locked validation | 409 RISK_APPROVAL_EXPIRED | Whole transaction rolls back; retry original key/payload |
| Existing key with different payload | 409 IDEMPOTENCY_CONFLICT | Restore the original payload; never repurpose the key |

Concurrent requests may have evaluated risk more than once, but unique receipt claims keep money once-only.
A failed request may race another attempt that successfully committed. Check/replay the original key before
concluding that nothing posted. Request failures expose neither remote descriptions nor raw risk envelopes.

## Read the retained decision

`GET /api/v1/transfers/{id}/risk-decision`, Authorization Bearer, CUSTOMER verified sender identity only.
200 application/json, Cache-Control:no-store, existing correlation contract:

```json
{
  "transferId": "<original transfer UUID>",
  "outcome": "APPROVED",
  "policyVersion": "amount-cap-v1-100000000",
  "reason": "AMOUNT_WITHIN_LIMIT",
  "assessedAt": "2026-11-03T03:00:00Z",
  "expiresAt": "2026-11-03T03:00:05Z"
}
```

Disabled mode: outcome BYPASSED, policyVersion disabled-v1, reason RISK_DISABLED, expiresAt null.
Expiry describes approval freshness when a new transfer was validated; it never invalidates an already POSTED
financial receipt or requires another risk call on replay. These fields stay immutable after service policy changes.

401 missing/invalid JWT;403 OPERATOR-only;404 TRANSFER_RISK_DECISION_NOT_FOUND for missing/other-sender/legacy
transfer (before V14), including transfers for which no durable result exists because preflight/transaction failed.
No raw request hash, issuer, subject, key, counterparty identity or remote response is returned.

This is a local deterministic demonstration, not production fraud/AML authorization. Holds/capture/cash-in/reversal
are not gated by this endpoint. No client-supplied approval can bypass the transfer API. Existing receipt/fee/statement/
SSE contracts remain authoritative within their documented scopes.
