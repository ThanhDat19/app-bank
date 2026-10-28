package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

/** Fixed two-line cash-in contract. Callers never supply ledger IDs or posting sides. */
@Service
public class LedgerCashInAccess {
    private final AccountFinancialAccess accounts;
    private final LedgerRepository ledger;
    private final LedgerPostingService posting;

    public LedgerCashInAccess(AccountFinancialAccess accounts, LedgerRepository ledger, LedgerPostingService posting) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
    }

    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID accountId, UUID referenceId, BigDecimal amount) {
        var account = accounts.lockActiveForPosting(Set.of(accountId)).getFirst();
        var linked = ledger.findForBank(accountId);
        if (account.ledgerProvisioned() != linked.isPresent()) {
            throw new BusinessException("LEDGER_LINK_INCONSISTENT", BusinessException.Kind.RULE_VIOLATION);
        }
        var destination = linked.orElseThrow(() -> new BusinessException("LEDGER_NOT_PROVISIONED", BusinessException.Kind.RULE_VIOLATION));
        var clearing = ledger.findCashClearing().orElseThrow(() -> new IllegalStateException("Cash clearing chart required"));
        return posting.post(new LedgerPostingCommand(referenceId, LedgerPostingCommand.Kind.SIMULATED_CASH_IN, List.of(
                new LedgerPostingCommand.Posting(clearing.id(), LedgerPostingCommand.Side.DEBIT, amount),
                new LedgerPostingCommand.Posting(destination.id(), LedgerPostingCommand.Side.CREDIT, amount))));
    }
}
