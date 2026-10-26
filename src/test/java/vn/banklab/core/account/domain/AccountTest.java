package vn.banklab.core.account.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.*;

class AccountTest {
    private Account account(AccountStatus status) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "100000000000", "VND", status,
                0, false, Instant.EPOCH, Instant.EPOCH);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> matrix() {
        return Stream.of(AccountStatus.values()).flatMap(state -> Stream.of(AccountAction.values())
                .map(action -> org.junit.jupiter.params.provider.Arguments.of(state, action)));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void enforcesEveryStateActionPair(AccountStatus status, AccountAction action) {
        var before = account(status);
        var reason = action == AccountAction.UNFREEZE ? AccountReason.REVIEW_CLEARED : AccountReason.CUSTOMER_REQUEST;
        boolean allowed = (status == AccountStatus.ACTIVE && action != AccountAction.UNFREEZE)
                || (status == AccountStatus.FROZEN && action == AccountAction.UNFREEZE);
        if (!allowed) {
            assertRule(AccountRuleViolation.Rule.INVALID_STATE,
                    () -> before.transition(0, action, reason, ClosureCheck.CLEAR, Instant.EPOCH.plusSeconds(1)));
        } else {
            var after = before.transition(0, action, reason, ClosureCheck.CLEAR, Instant.EPOCH.plusSeconds(1));
            assertThat(after.status()).isEqualTo(switch (action) {
                case FREEZE -> AccountStatus.FROZEN;
                case UNFREEZE -> AccountStatus.ACTIVE;
                case CLOSE -> AccountStatus.CLOSED;
            });
            assertThat(after.version()).isEqualTo(1);
            assertThat(before.version()).isZero();
            assertThat(before.status()).isEqualTo(status);
            assertThat(after.createdAt()).isEqualTo(before.createdAt());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClosureCheck.class, names = "CLEAR", mode = EnumSource.Mode.EXCLUDE)
    @NullSource
    void closeRejectsBlockedOrUnknownFinancialState(ClosureCheck check) {
        assertThatThrownBy(() -> account(AccountStatus.ACTIVE).transition(0, AccountAction.CLOSE,
                AccountReason.CUSTOMER_REQUEST, check, Instant.EPOCH)).isInstanceOf(AccountRuleViolation.class);
    }

    @ParameterizedTest
    @EnumSource(AccountAction.class)
    void reasonCannotBeReusedAcrossUnrelatedOperations(AccountAction action) {
        var state = action == AccountAction.UNFREEZE ? AccountStatus.FROZEN : AccountStatus.ACTIVE;
        assertRule(AccountRuleViolation.Rule.INVALID_REASON, () -> account(state).transition(0, action,
                AccountReason.OPENED, ClosureCheck.CLEAR, Instant.EPOCH));
    }

    @Test
    void staleVersionPreventsStateChange() {
        assertRule(AccountRuleViolation.Rule.STALE_VERSION, () -> account(AccountStatus.ACTIVE).transition(1,
                AccountAction.CLOSE, AccountReason.CUSTOMER_REQUEST, ClosureCheck.CLEAR, Instant.EPOCH));
    }

    private static void assertRule(AccountRuleViolation.Rule rule, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AccountRuleViolation.class,
                error -> assertThat(error.rule()).isEqualTo(rule));
    }
}
