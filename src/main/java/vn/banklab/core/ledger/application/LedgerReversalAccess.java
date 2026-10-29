package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.shared.application.BusinessException;

/** Internal fixed full reversal contract. The financial use case also verifies its public receipt. */
@Service
@PreAuthorize("hasRole('OPERATOR')")
public class LedgerReversalAccess {
    public record Original(UUID id, String kind, BigDecimal amount) { }
    private final LedgerRepository ledger;
    private final LedgerPostingService posting;

    public LedgerReversalAccess(LedgerRepository ledger, LedgerPostingService posting) {
        this.ledger = ledger;
        this.posting = posting;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Original lockOriginal(UUID id) {
        var journal = ledger.lockJournal(id).orElseThrow(() ->
                new BusinessException("REVERSAL_ORIGINAL_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
        if (!"POSTED".equals(journal.status()) ||
                (journal.kind() != LedgerPostingCommand.Kind.SIMULATED_CASH_IN && journal.kind() != LedgerPostingCommand.Kind.INTERNAL_TRANSFER)) {
            throw new BusinessException("REVERSAL_NOT_SUPPORTED", BusinessException.Kind.RULE_VIOLATION);
        }
        var lines = ledger.postings(id);
        if (lines.size() != 2 || lines.getFirst().amount().compareTo(lines.getLast().amount()) != 0) {
            throw new BusinessException("REVERSAL_NOT_SUPPORTED", BusinessException.Kind.RULE_VIOLATION);
        }
        return new Original(id, journal.kind().name(), lines.getFirst().amount());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postInverse(UUID originalId, UUID referenceId) {
        lockOriginal(originalId);
        var inverse = ledger.postings(originalId).stream().map(line -> new LedgerPostingCommand.Posting(
                line.ledgerAccountId(), line.side() == LedgerPostingCommand.Side.DEBIT
                ? LedgerPostingCommand.Side.CREDIT : LedgerPostingCommand.Side.DEBIT, line.amount())).toList();
        return posting.post(new LedgerPostingCommand(referenceId, LedgerPostingCommand.Kind.REVERSAL, inverse));
    }
}
