package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Internal application command. No generic REST endpoint accepts journals.
 */
public record LedgerPostingCommand(UUID referenceId, Kind kind, List<Posting> postings) {
    public enum Kind {SIMULATED_CASH_IN, INTERNAL_TRANSFER, REVERSAL, ADJUSTMENT}

    public enum Side {DEBIT, CREDIT}

    public record Posting(UUID ledgerAccountId, Side side, BigDecimal amount) {
    }
}
