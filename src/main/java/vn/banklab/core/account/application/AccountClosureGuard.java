package vn.banklab.core.account.application;

import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.ClosureCheck;

/**
 * Called with the account row locked. A future ledger adapter must join the same transaction.
 */
public interface AccountClosureGuard {
    ClosureCheck check(Account account);
}
