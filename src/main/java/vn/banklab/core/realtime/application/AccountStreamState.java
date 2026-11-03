package vn.banklab.core.realtime.application;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.ledger.application.LedgerStreamAccess;
import vn.banklab.core.notification.application.NotificationService;

@Service
public class AccountStreamState {
    public record Version(AccountFinancialAccess.ReadVersion account,
                          LedgerStreamAccess.Revision ledger, long notifications) { }
    private final AccountFinancialAccess accounts;
    private final LedgerStreamAccess ledger;
    private final NotificationService notifications;

    public AccountStreamState(AccountFinancialAccess accounts, LedgerStreamAccess ledger,
                              NotificationService notifications) {
        this.accounts = accounts; this.ledger = ledger; this.notifications = notifications;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 3)
    public Version read(UUID accountId) {
        // Re-authorize every sample. No DB transaction remains open during a network write or wait.
        var account = accounts.readVersion(accountId);
        return new Version(account, ledger.revision(accountId), notifications.changeCount(accountId));
    }
}
