package vn.banklab.core.customer.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import vn.banklab.core.customer.domain.Customer;
import vn.banklab.core.customer.domain.KycStatus;

@Schema(description = "Synthetic customer profile; identity links and reviewer identities are not exposed")
public record CustomerResponse(UUID id, String displayName, KycStatus kycStatus, long version,
                               Instant createdAt, Instant updatedAt) {
    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.id(), customer.displayName(), customer.kycStatus(), customer.version(),
                customer.createdAt(), customer.updatedAt());
    }
}
