package vn.banklab.core.risk.application;

import java.time.Instant;

/** Internal preflight contract. Call with no transaction; authorization belongs to the transfer caller. */
public interface TransferRiskGate {
    record Approval(String requestId, String outcome, String policyVersion, String reason,
                    Instant assessedAt, Instant expiresAt) { }
    Approval assess(String requestId, String amount);
}
