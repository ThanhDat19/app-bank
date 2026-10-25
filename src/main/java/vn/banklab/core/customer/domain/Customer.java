package vn.banklab.core.customer.domain;

import java.time.Instant;
import java.util.UUID;

import static vn.banklab.core.customer.domain.KycRuleViolation.Rule.*;

public record Customer(UUID id, String identityIssuer, String identitySubject, String displayName,
                       KycStatus kycStatus, long version, Instant createdAt, Instant updatedAt) {

    public static Customer create(UUID id, String issuer, String subject, String displayName, Instant now) {
        return new Customer(id, issuer, subject, displayName, KycStatus.PENDING, 0, now, now);
    }

    public Customer decide(long expectedVersion, KycDecision decision, KycReason reason,
                           String reviewerIssuer, String reviewerSubject, Instant now) {
        if (version != expectedVersion) { throw new KycRuleViolation(STALE_VERSION); }
        if (kycStatus != KycStatus.PENDING) { throw new KycRuleViolation(ALREADY_DECIDED); }
        if (identityIssuer.equals(reviewerIssuer) && identitySubject.equals(reviewerSubject)) {
            throw new KycRuleViolation(SELF_REVIEW);
        }
        if (!decision.accepts(reason)) { throw new KycRuleViolation(INVALID_REASON); }
        return new Customer(id, identityIssuer, identitySubject, displayName,
                decision.status(), version + 1, createdAt, now);
    }
}
