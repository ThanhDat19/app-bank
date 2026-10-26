package vn.banklab.core.account.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.domain.*;
import vn.banklab.core.customer.application.CustomerAccountAccess;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.application.BusinessException;

import static vn.banklab.core.shared.application.BusinessException.Kind.*;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository accounts;
    private final CustomerAccountAccess customers;
    private final CurrentActorProvider actors;
    private final AccountClosureGuard closure;

    public AccountService(AccountRepository accounts, CustomerAccountAccess customers,
                          CurrentActorProvider actors, AccountClosureGuard closure) {
        this.accounts = accounts;
        this.customers = customers;
        this.actors = actors;
        this.closure = closure;
    }

    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public Account open(String currency) {
        if (!"VND".equals(currency)) {
            throw new BusinessException("ACCOUNT_CURRENCY_UNSUPPORTED", RULE_VIOLATION);
        }
        UUID customerId = customers.requireVerifiedOwnerForOpening();
        var account = accounts.insert(UUID.randomUUID(), customerId, currency, now()).orElseThrow(() ->
                new BusinessException("ACCOUNT_ALREADY_EXISTS", CONFLICT));
        accounts.appendHistory(null, account, AccountReason.OPENED, actors.currentActor());
        return account;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public List<Account> mine() {
        return customers.ownedCustomerId().map(accounts::findByCustomer).orElseGet(List::of);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public Account owned(UUID id) {
        return owned(id, false);
    }

    private Account owned(UUID id, boolean lock) {
        UUID customerId = customers.ownedCustomerId().orElseThrow(AccountService::notFound);
        return accounts.findOwned(id, customerId, lock).orElseThrow(AccountService::notFound);
    }

    @PreAuthorize("hasRole('OPERATOR')")
    public Account forOperations(UUID id) {
        return accounts.findById(id, false).orElseThrow(AccountService::notFound);
    }

    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public Account close(UUID id, long version, AccountReason reason) {
        var before = owned(id, true);
        return change(before, version, AccountAction.CLOSE, reason, closure.check(before));
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public Account operate(UUID id, long version, AccountAction action, AccountReason reason) {
        if (action == AccountAction.CLOSE) {
            throw new BusinessException("ACCOUNT_OPERATION_INVALID", RULE_VIOLATION);
        }
        var before = accounts.findById(id, true).orElseThrow(AccountService::notFound);
        if (customers.isCurrentActorOwner(before.customerId())) {
            throw new BusinessException("ACCOUNT_SELF_OPERATION_FORBIDDEN", RULE_VIOLATION);
        }
        return change(before, version, action, reason, ClosureCheck.UNAVAILABLE);
    }

    private Account change(Account before, long version, AccountAction action, AccountReason reason, ClosureCheck check) {
        Account after;
        try {
            after = before.transition(version, action, reason, check, now());
        } catch (AccountRuleViolation violation) {
            throw switch (violation.rule()) {
                case STALE_VERSION -> new BusinessException("ACCOUNT_VERSION_CONFLICT", CONFLICT);
                case INVALID_STATE -> new BusinessException("ACCOUNT_STATE_INVALID", CONFLICT);
                case INVALID_REASON -> new BusinessException("ACCOUNT_REASON_INVALID", RULE_VIOLATION);
                case NON_ZERO_BALANCE -> new BusinessException("ACCOUNT_BALANCE_NOT_ZERO", RULE_VIOLATION);
                case ACTIVE_HOLDS -> new BusinessException("ACCOUNT_HAS_HOLDS", RULE_VIOLATION);
                case PENDING_PAYMENTS -> new BusinessException("ACCOUNT_HAS_PENDING_PAYMENTS", RULE_VIOLATION);
                case CLOSURE_UNAVAILABLE -> new BusinessException("ACCOUNT_CLOSURE_UNAVAILABLE", RULE_VIOLATION);
            };
        }
        if (!accounts.update(before, after)) {
            throw new BusinessException("ACCOUNT_VERSION_CONFLICT", CONFLICT);
        }
        accounts.appendHistory(before, after, reason, actors.currentActor());
        return after;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException notFound() {
        return new BusinessException("ACCOUNT_NOT_FOUND", NOT_FOUND);
    }
}
