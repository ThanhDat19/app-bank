package vn.banklab.core.account.domain;

import java.time.Instant;
import java.util.UUID;

import static vn.banklab.core.account.domain.AccountRuleViolation.Rule.*;

public record Account(UUID id, UUID customerId, String accountNumber, String currency,
                      AccountStatus status, long version, boolean ledgerProvisioned,
                      Instant createdAt, Instant updatedAt) {
    public Account transition(long expectedVersion, AccountAction action, AccountReason reason,
                              ClosureCheck closure, Instant now) {
        if (version != expectedVersion) {
            throw new AccountRuleViolation(STALE_VERSION);
        }
        AccountStatus next = switch (action) {
            case FREEZE -> {
                if (status != AccountStatus.ACTIVE) {
                    throw new AccountRuleViolation(INVALID_STATE);
                }
                if (reason != AccountReason.CUSTOMER_REQUEST && reason != AccountReason.SECURITY_REVIEW) {
                    throw new AccountRuleViolation(INVALID_REASON);
                }
                yield AccountStatus.FROZEN;
            }
            case UNFREEZE -> {
                if (status != AccountStatus.FROZEN) {
                    throw new AccountRuleViolation(INVALID_STATE);
                }
                if (reason != AccountReason.REVIEW_CLEARED) {
                    throw new AccountRuleViolation(INVALID_REASON);
                }
                yield AccountStatus.ACTIVE;
            }
            case CLOSE -> {
                if (status != AccountStatus.ACTIVE) {
                    throw new AccountRuleViolation(INVALID_STATE);
                }
                if (reason != AccountReason.CUSTOMER_REQUEST) {
                    throw new AccountRuleViolation(INVALID_REASON);
                }
                if (closure == null) {
                    throw new AccountRuleViolation(CLOSURE_UNAVAILABLE);
                }
                if (closure != ClosureCheck.CLEAR) {
                    throw new AccountRuleViolation(switch (closure) {
                        case NON_ZERO_BALANCE -> NON_ZERO_BALANCE;
                        case ACTIVE_HOLDS -> ACTIVE_HOLDS;
                        case PENDING_PAYMENTS -> PENDING_PAYMENTS;
                        default -> CLOSURE_UNAVAILABLE;
                    });
                }
                yield AccountStatus.CLOSED;
            }
        };
        return new Account(id, customerId, accountNumber, currency, next, version + 1,
                ledgerProvisioned, createdAt, now);
    }
}
