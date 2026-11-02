package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

/** Authorized two-account posting; no source-first lock or client-supplied ledger IDs. */
@Service
public class LedgerTransferAccess {
    private final AccountFinancialAccess accounts;
    private final LedgerRepository ledger;
    private final LedgerPostingService posting;

    public LedgerTransferAccess(AccountFinancialAccess accounts, LedgerRepository ledger, LedgerPostingService posting) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID sourceId, UUID destinationId, UUID referenceId, BigDecimal amount) {
        if (sourceId == null || destinationId == null || sourceId.equals(destinationId)) {
            throw new BusinessException("TRANSFER_ACCOUNTS_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        var pair = accounts.lockOwnedTransferPair(sourceId, destinationId);
        var links = pair.stream().map(account -> {
            var linked = ledger.findForBank(account.id());
            if (account.ledgerProvisioned() != linked.isPresent()) {
                throw new BusinessException("LEDGER_LINK_INCONSISTENT", BusinessException.Kind.RULE_VIOLATION);
            }
            return linked.orElseThrow(() -> new BusinessException("LEDGER_NOT_PROVISIONED", BusinessException.Kind.RULE_VIOLATION));
        }).toList();
        var source = links.stream().filter(a -> a.bankAccountId().equals(sourceId)).findFirst().orElseThrow();
        var destination = links.stream().filter(a -> a.bankAccountId().equals(destinationId)).findFirst().orElseThrow();
        return posting.post(new LedgerPostingCommand(referenceId, LedgerPostingCommand.Kind.INTERNAL_TRANSFER, List.of(
                new LedgerPostingCommand.Posting(source.id(), LedgerPostingCommand.Side.DEBIT, amount),
                new LedgerPostingCommand.Posting(destination.id(), LedgerPostingCommand.Side.CREDIT, amount))));
    }
}
