package vn.banklab.core.customer.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vn.banklab.core.customer.domain.KycDecision;
import vn.banklab.core.customer.domain.KycReason;

public record KycDecisionRequest(
        @NotNull @PositiveOrZero @Schema(example = "0", description = "Version read from the customer profile") Long expectedVersion,
        @NotNull @Schema(example = "VERIFIED") KycDecision decision,
        @NotNull @Schema(example = "DOCUMENTS_MATCH", description = "VERIFIED: DOCUMENTS_MATCH; REJECTED: INCOMPLETE or INCONSISTENT") KycReason reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown request field");
    }
}
