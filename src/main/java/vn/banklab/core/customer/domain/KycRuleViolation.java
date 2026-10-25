package vn.banklab.core.customer.domain;

public final class KycRuleViolation extends RuntimeException {
    public enum Rule { STALE_VERSION, ALREADY_DECIDED, SELF_REVIEW, INVALID_REASON }

    private final Rule rule;

    public KycRuleViolation(Rule rule) {
        super("KYC decision rejected");
        this.rule = rule;
    }

    public Rule rule() { return rule; }
}
