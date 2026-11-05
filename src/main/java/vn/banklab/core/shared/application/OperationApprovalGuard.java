package vn.banklab.core.shared.application;

import java.util.UUID;

/** Requires a matching two-person execution claim in the current database transaction. */
public interface OperationApprovalGuard {
    void require(String type, UUID target, Long expectedAccountVersion, String reason);
}
