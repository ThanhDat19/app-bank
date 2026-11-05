package vn.banklab.core.account.application;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.domain.AccountAction;
import vn.banklab.core.account.domain.AccountReason;
import vn.banklab.core.customer.application.CustomerAccountAccess;
import vn.banklab.core.shared.application.BusinessException;

/** Application contract for the operations workflow; customer identity is not exposed. */
@Service
@PreAuthorize("hasRole('OPERATOR')")
public class AccountOperationAccess {
    public record Result(UUID id, String accountNumber, String currency, String status, long version,
                         java.time.Instant createdAt, java.time.Instant updatedAt) { }
    private final AccountService service;
    private final CustomerAccountAccess customers;
    public AccountOperationAccess(AccountService service, CustomerAccountAccess customers) {
        this.service = service; this.customers = customers;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateMaker(UUID id) {
        if (customers.isCurrentActorOwner(service.forOperations(id).customerId()))
            throw new BusinessException("ACCOUNT_SELF_OPERATION_FORBIDDEN", BusinessException.Kind.RULE_VIOLATION);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Result execute(UUID id, long version, String type, String reason) {
        var a = service.operate(id, version, AccountAction.valueOf(type), AccountReason.valueOf(reason));
        return new Result(a.id(), a.accountNumber(), a.currency(), a.status().name(), a.version(), a.createdAt(), a.updatedAt());
    }
}
