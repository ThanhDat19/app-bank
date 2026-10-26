package vn.banklab.core.customer.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.customer.domain.KycStatus;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.application.BusinessException;

/**
 * Cross-module contract: account code never reads customer tables or customer domain internals.
 */
@Service
public class CustomerAccountAccess {
    private final CustomerRepository customers;
    private final CurrentActorProvider actors;

    public CustomerAccountAccess(CustomerRepository customers, CurrentActorProvider actors) {
        this.customers = customers;
        this.actors = actors;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireVerifiedOwnerForOpening() {
        var actor = actors.currentActor();
        var customer = customers.findByIdentityForUpdate(actor.issuer(), actor.subject()).orElseThrow(() ->
                new BusinessException("CUSTOMER_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
        if (customer.kycStatus() != KycStatus.VERIFIED) {
            throw new BusinessException("KYC_NOT_VERIFIED", BusinessException.Kind.RULE_VIOLATION);
        }
        return customer.id();
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Optional<UUID> ownedCustomerId() {
        var actor = actors.currentActor();
        return customers.findByIdentity(actor.issuer(), actor.subject()).map(customer -> customer.id());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OPERATOR')")
    public boolean isCurrentActorOwner(UUID customerId) {
        var actor = actors.currentActor();
        return customers.findOwned(customerId, actor.issuer(), actor.subject()).isPresent();
    }
}
