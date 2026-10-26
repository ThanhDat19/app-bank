package vn.banklab.core.account.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.AccountStatus;
import vn.banklab.core.customer.application.CustomerAccountAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class AccountFinancialAccess {
    public record Reference(UUID id, String currency, boolean ledgerProvisioned) {
    }

    private final AccountRepository accounts;
    private final CustomerAccountAccess customers;

    public AccountFinancialAccess(AccountRepository accounts, CustomerAccountAccess customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation = Propagation.MANDATORY)
    public Reference lockOwnedActive(UUID id) {
        var customerId = customers.ownedCustomerId().orElseThrow(AccountFinancialAccess::notFound);
        return active(accounts.findOwned(id, customerId, true).orElseThrow(AccountFinancialAccess::notFound));
    }

    /**
     * Trusted financial application callers must perform command-specific authorization before invoking this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Reference> lockActiveForPosting(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var locked = accounts.lockByIds(ids);
        if (locked.size() != ids.size()) {
            throw notFound();
        }
        return locked.stream().map(AccountFinancialAccess::active).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markProvisioned(UUID id) {
        accounts.markLedgerProvisioned(id);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Reference owned(UUID id) {
        var customerId = customers.ownedCustomerId().orElseThrow(AccountFinancialAccess::notFound);
        return reference(accounts.findOwned(id, customerId, false).orElseThrow(AccountFinancialAccess::notFound));
    }

    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(readOnly = true)
    public Reference forOperations(UUID id) {
        return reference(accounts.findById(id, false).orElseThrow(AccountFinancialAccess::notFound));
    }

    private static Reference active(Account account) {
        if (account.status() != AccountStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_STATE_INVALID", BusinessException.Kind.CONFLICT);
        }
        return reference(account);
    }

    private static Reference reference(Account account) {
        return new Reference(account.id(), account.currency(), account.ledgerProvisioned());
    }

    private static BusinessException notFound() {
        return new BusinessException("ACCOUNT_NOT_FOUND", BusinessException.Kind.NOT_FOUND);
    }
}
