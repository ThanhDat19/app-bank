package vn.banklab.core.account.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vn.banklab.core.account.domain.AccountReason;

public record AccountStateRequest(@NotNull @PositiveOrZero @Schema(example = "0") Long expectedVersion,
                                  @NotNull @Schema(example = "CUSTOMER_REQUEST",
                                          description = "Freeze: CUSTOMER_REQUEST/SECURITY_REVIEW. Unfreeze: REVIEW_CLEARED. Close: CUSTOMER_REQUEST.") AccountReason reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown request field");
    }
}
