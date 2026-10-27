package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
@Transactional(readOnly = true)
public class LedgerQueryService {
    public record Balance(UUID accountId, String currency, BigDecimal postedBalance) {
    }

    public enum Closure {CLEAR, NON_ZERO, UNAVAILABLE}

    private final LedgerRepository ledger;
    private final AccountFinancialAccess accounts;

    public LedgerQueryService(LedgerRepository ledger, AccountFinancialAccess accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public Balance mine(UUID id) {
        return balance(accounts.owned(id));
    }

    @PreAuthorize("hasRole('OPERATOR')")
    public Balance forOperations(UUID id) {
        return balance(accounts.forOperations(id));
    }

    private Balance balance(AccountFinancialAccess.Reference account) {
        var linked = ledger.findForBank(account.id());
        if (linked.isEmpty()) {
            throw new BusinessException(account.ledgerProvisioned() ? "LEDGER_LINK_INCONSISTENT" : "LEDGER_NOT_PROVISIONED",
                    BusinessException.Kind.RULE_VIOLATION);
        }
        // Provisioning may commit between the ownership read and this lookup. The DB commit constraint
        // guarantees a visible link has its marker; do not reject a valid link using an older marker snapshot.
        return new Balance(account.id(), linked.get().currency(), ledger.balance(linked.get().id()));
    }

    /**
     * The account caller holds its row lock; this is not a standalone authorization endpoint.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Closure assessClosure(UUID id, boolean marker) {
        var linked = ledger.findForBank(id);
        if (marker != linked.isPresent()) {
            return Closure.UNAVAILABLE;
        }
        if (linked.isEmpty()) {
            return Closure.CLEAR;
        }
        return ledger.balance(linked.get().id()).signum() == 0 ? Closure.CLEAR : Closure.NON_ZERO;
    }
}
