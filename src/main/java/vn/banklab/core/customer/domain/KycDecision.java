package vn.banklab.core.customer.domain;

public enum KycDecision {
    VERIFIED, REJECTED;

    public boolean accepts(KycReason reason) {
        return this == VERIFIED ? reason == KycReason.DOCUMENTS_MATCH
                : reason == KycReason.INCOMPLETE || reason == KycReason.INCONSISTENT;
    }

    public KycStatus status() {
        return this == VERIFIED ? KycStatus.VERIFIED : KycStatus.REJECTED;
    }
}
