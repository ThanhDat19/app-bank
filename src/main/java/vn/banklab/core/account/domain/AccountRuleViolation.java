package vn.banklab.core.account.domain;

public final class AccountRuleViolation extends RuntimeException {
    public enum Rule {STALE_VERSION, INVALID_STATE, INVALID_REASON, NON_ZERO_BALANCE, ACTIVE_HOLDS, PENDING_PAYMENTS, CLOSURE_UNAVAILABLE}

    private final Rule rule;

    public AccountRuleViolation(Rule rule) {
        super("Account operation rejected");
        this.rule = rule;
    }

    public Rule rule() {
        return rule;
    }
}
