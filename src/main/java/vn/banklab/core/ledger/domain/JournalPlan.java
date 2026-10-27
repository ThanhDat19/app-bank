package vn.banklab.core.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static vn.banklab.core.ledger.domain.LedgerRuleViolation.Rule.*;

public record JournalPlan(List<Entry> entries) {
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999999");

    public record Entry(UUID ledgerAccountId, PostingSide side, BigDecimal amount) {
    }

    public JournalPlan {
        if (entries == null || entries.size() < 2 || entries.size() > 100 || entries.stream().anyMatch(e ->
                e == null || e.ledgerAccountId() == null || e.side() == null)) {
            throw new LedgerRuleViolation(INVALID_POSTINGS);
        }
        entries = List.copyOf(entries);
        if (entries.stream().map(Entry::ledgerAccountId).distinct().count() < 2) {
            throw new LedgerRuleViolation(INVALID_POSTINGS);
        }
        for (var entry : entries) {
            var amount = entry.amount();
            if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                throw new LedgerRuleViolation(INVALID_AMOUNT);
            }
        }
        var debits = entries.stream().filter(e -> e.side() == PostingSide.DEBIT).map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var credits = entries.stream().filter(e -> e.side() == PostingSide.CREDIT).map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new LedgerRuleViolation(UNBALANCED);
        }
    }
}
