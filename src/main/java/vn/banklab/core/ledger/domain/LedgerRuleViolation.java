package vn.banklab.core.ledger.domain;

public final class LedgerRuleViolation extends RuntimeException {
    public enum Rule {INVALID_POSTINGS, INVALID_AMOUNT, UNBALANCED}

    private final Rule rule;

    public LedgerRuleViolation(Rule rule) {
        super("Ledger rule rejected");
        this.rule = rule;
    }

    public Rule rule() {
        return rule;
    }
}
