package vn.banklab.core.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class JournalPlanTest {
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();

    private JournalPlan.Entry line(UUID id, PostingSide side, String amount) {
        return new JournalPlan.Entry(id, side, new BigDecimal(amount));
    }

    @Test
    void acceptsBalancedExactDecimalArithmeticAndDefensiveCopy() {
        var entries = new java.util.ArrayList<>(List.of(line(first, PostingSide.DEBIT, "300"),
                line(second, PostingSide.CREDIT, "100.00"), line(second, PostingSide.CREDIT, "200")));
        var plan = new JournalPlan(entries);
        entries.clear();
        assertThat(plan.entries()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.5", "100.001", "100000000000000000000"})
    @NullSource
    void rejectsInvalidAmounts(String amount) {
        var entry = new JournalPlan.Entry(first, PostingSide.DEBIT, amount == null ? null : new BigDecimal(amount));
        assertRule(LedgerRuleViolation.Rule.INVALID_AMOUNT, () -> new JournalPlan(List.of(entry, line(second, PostingSide.CREDIT, "1"))));
    }

    @Test
    void rejectsUnbalancedJournal() {
        assertRule(LedgerRuleViolation.Rule.UNBALANCED, () -> new JournalPlan(List.of(line(first, PostingSide.DEBIT, "2"), line(second, PostingSide.CREDIT, "1"))));
    }

    static Stream<List<JournalPlan.Entry>> invalidLines() {
        UUID id = UUID.randomUUID();
        var line = new JournalPlan.Entry(id, PostingSide.DEBIT, BigDecimal.ONE);
        return Stream.of(List.of(), List.of(line), java.util.Arrays.asList(line, null),
                List.of(line, new JournalPlan.Entry(null, PostingSide.CREDIT, BigDecimal.ONE)),
                List.of(line, new JournalPlan.Entry(UUID.randomUUID(), null, BigDecimal.ONE)),
                List.of(line, new JournalPlan.Entry(id, PostingSide.CREDIT, BigDecimal.ONE)),
                java.util.Collections.nCopies(101, line));
    }

    @ParameterizedTest
    @MethodSource("invalidLines")
    @NullSource
    void rejectsInvalidPostingStructure(List<JournalPlan.Entry> entries) {
        assertRule(LedgerRuleViolation.Rule.INVALID_POSTINGS, () -> new JournalPlan(entries));
    }

    private static void assertRule(LedgerRuleViolation.Rule rule, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LedgerRuleViolation.class, error -> assertThat(error.rule()).isEqualTo(rule));
    }
}
