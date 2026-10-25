package vn.banklab.core.customer.application;

import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.customer.domain.Customer;
import vn.banklab.core.customer.domain.KycReason;
import vn.banklab.core.iam.application.Actor;

public interface CustomerRepository {
    boolean insert(Customer customer);
    Optional<Customer> findByIdentity(String issuer, String subject);
    Optional<Customer> findOwned(UUID id, String issuer, String subject);
    Optional<Customer> findById(UUID id);
    boolean updateDecision(Customer before, Customer after);
    void appendDecision(Customer after, Actor reviewer, KycReason reason);
}
