package vn.banklab.core.customer.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.customer.domain.Customer;
import vn.banklab.core.customer.domain.KycDecision;
import vn.banklab.core.customer.domain.KycReason;
import vn.banklab.core.customer.domain.KycRuleViolation;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.application.BusinessException;

import static vn.banklab.core.shared.application.BusinessException.Kind.*;

@Service
@Transactional(readOnly = true)
public class CustomerService {
    private final CustomerRepository customers;
    private final CurrentActorProvider actors;

    public CustomerService(CustomerRepository customers, CurrentActorProvider actors) {
        this.customers = customers;
        this.actors = actors;
    }

    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public Customer createMine(String displayName) {
        var actor = actors.currentActor();
        var customer = Customer.create(UUID.randomUUID(), actor.issuer(), actor.subject(), displayName, now());
        if (!customers.insert(customer)) {
            throw new BusinessException("CUSTOMER_ALREADY_EXISTS", CONFLICT);
        }
        return customer;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public Customer getMine() {
        var actor = actors.currentActor();
        return customers.findByIdentity(actor.issuer(), actor.subject()).orElseThrow(CustomerService::notFound);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public Customer getOwned(UUID id) {
        var actor = actors.currentActor();
        return customers.findOwned(id, actor.issuer(), actor.subject()).orElseThrow(CustomerService::notFound);
    }

    @PreAuthorize("hasRole('OPERATOR')")
    public Customer getForOperations(UUID id) {
        return customers.findById(id).orElseThrow(CustomerService::notFound);
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public Customer decide(UUID id, long expectedVersion, KycDecision decision, KycReason reason) {
        var reviewer = actors.currentActor();
        var before = customers.findById(id).orElseThrow(CustomerService::notFound);
        Customer after;
        try {
            after = before.decide(expectedVersion, decision, reason,
                    reviewer.issuer(), reviewer.subject(), now());
        } catch (KycRuleViolation violation) {
            throw switch (violation.rule()) {
                case STALE_VERSION -> new BusinessException("CUSTOMER_VERSION_CONFLICT", CONFLICT);
                case ALREADY_DECIDED -> new BusinessException("KYC_ALREADY_DECIDED", CONFLICT);
                case SELF_REVIEW -> new BusinessException("KYC_SELF_REVIEW_FORBIDDEN", RULE_VIOLATION);
                case INVALID_REASON -> new BusinessException("KYC_REASON_INVALID", RULE_VIOLATION);
            };
        }
        if (!customers.updateDecision(before, after)) {
            throw new BusinessException("CUSTOMER_VERSION_CONFLICT", CONFLICT);
        }
        customers.appendDecision(after, reviewer, reason);
        return after;
    }

    private static BusinessException notFound() {
        return new BusinessException("CUSTOMER_NOT_FOUND", NOT_FOUND);
    }

    private static Instant now() {
        // PostgreSQL timestamps retain microseconds; write and subsequent GET must agree exactly.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
