package vn.banklab.core.account.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.AccountReason;
import vn.banklab.core.iam.application.Actor;

public interface AccountRepository {
    Optional<Account> insert(UUID id, UUID customerId, String currency, Instant now);

    List<Account> findByCustomer(UUID customerId);

    Optional<Account> findOwned(UUID id, UUID customerId, boolean lock);

    Optional<Account> findById(UUID id, boolean lock);

    boolean update(Account before, Account after);

    void appendHistory(Account before, Account after, AccountReason reason, Actor actor);
}
