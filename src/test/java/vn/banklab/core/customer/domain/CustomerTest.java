package vn.banklab.core.customer.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;
import static vn.banklab.core.customer.domain.KycRuleViolation.Rule.*;

class CustomerTest {
    private final Instant created = Instant.parse("2026-10-25T00:00:00Z");
    private final Customer pending = Customer.create(UUID.randomUUID(), "issuer-a", "owner", "Demo", created);

    @ParameterizedTest
    @EnumSource(KycReason.class)
    void recordsValidDecisionWithoutMutatingOriginal(KycReason reason) {
        var decision = reason == KycReason.DOCUMENTS_MATCH ? KycDecision.VERIFIED : KycDecision.REJECTED;
        var result = pending.decide(0, decision, reason, "issuer-a", "reviewer", created.plusSeconds(5));
        assertThat(result.kycStatus()).isEqualTo(decision.status());
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.createdAt()).isEqualTo(created);
        assertThat(result.updatedAt()).isEqualTo(created.plusSeconds(5));
        assertThat(pending.kycStatus()).isEqualTo(KycStatus.PENDING);
        assertThat(pending.version()).isZero();
    }

    @Test
    void staleVersionCannotBeUsedForDecision() {
        assertRule(STALE_VERSION, () -> pending.decide(1, KycDecision.VERIFIED,
                KycReason.DOCUMENTS_MATCH, "issuer-a", "reviewer", created));
    }

    @ParameterizedTest
    @EnumSource(KycDecision.class)
    void terminalStateCannotBeDecidedAgain(KycDecision decision) {
        var reason = decision == KycDecision.VERIFIED ? KycReason.DOCUMENTS_MATCH : KycReason.INCOMPLETE;
        var decided = pending.decide(0, decision, reason, "issuer-a", "reviewer", created);
        assertRule(ALREADY_DECIDED, () -> decided.decide(1, KycDecision.REJECTED,
                KycReason.INCOMPLETE, "issuer-a", "reviewer", created));
    }

    @Test
    void sameIdentityCannotReviewItself() {
        assertRule(SELF_REVIEW, () -> pending.decide(0, KycDecision.VERIFIED,
                KycReason.DOCUMENTS_MATCH, "issuer-a", "owner", created));
    }

    @Test
    void sameSubjectUnderDifferentIssuerIsDifferentIdentity() {
        assertThat(pending.decide(0, KycDecision.VERIFIED, KycReason.DOCUMENTS_MATCH,
                "issuer-b", "owner", created).kycStatus()).isEqualTo(KycStatus.VERIFIED);
    }

    @ParameterizedTest
    @EnumSource(KycReason.class)
    void incompatibleDecisionReasonIsRejected(KycReason reason) {
        var decision = reason == KycReason.DOCUMENTS_MATCH ? KycDecision.REJECTED : KycDecision.VERIFIED;
        assertRule(INVALID_REASON, () -> pending.decide(0, decision, reason, "issuer-a", "reviewer", created));
    }

    private static void assertRule(KycRuleViolation.Rule rule, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(KycRuleViolation.class,
                exception -> assertThat(exception.rule()).isEqualTo(rule));
    }
}
