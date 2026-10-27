package vn.banklab.core.ledger.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class LedgerProvisioningService {
    private final LedgerRepository ledger;
    private final AccountFinancialAccess accounts;

    public LedgerProvisioningService(LedgerRepository ledger, AccountFinancialAccess accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public LedgerRepository.LedgerAccount provisionMine(UUID accountId) {
        var account = accounts.lockOwnedActive(accountId);
        var existing = ledger.findForBank(accountId);
        if (account.ledgerProvisioned() != existing.isPresent()) {
            throw new BusinessException("LEDGER_LINK_INCONSISTENT", BusinessException.Kind.RULE_VIOLATION);
        }
        if (existing.isPresent()) {
            return existing.get();
        }
        var created = ledger.insertCustomer(accountId, account.currency(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        accounts.markProvisioned(accountId);
        return created;
    }
}
