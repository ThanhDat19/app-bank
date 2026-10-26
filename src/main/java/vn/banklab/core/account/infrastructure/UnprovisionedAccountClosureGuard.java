package vn.banklab.core.account.infrastructure;

import org.springframework.stereotype.Component;
import vn.banklab.core.account.application.AccountClosureGuard;
import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.ClosureCheck;

@Component
public class UnprovisionedAccountClosureGuard implements AccountClosureGuard {
    @Override
    public ClosureCheck check(Account account) {
        // Never infer zero funds/holds from a missing ledger integration.
        return account.ledgerProvisioned() ? ClosureCheck.UNAVAILABLE : ClosureCheck.CLEAR;
    }
}
