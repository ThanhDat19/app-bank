package vn.banklab.core.customer.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Synthetic profile only; owner and initial KYC state are assigned by the server")
public record CreateCustomerRequest(
        @NotBlank @Size(min = 2, max = 80) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*")
        @Schema(example = "Customer Demo A", description = "Synthetic display name, trimmed; 2–80 characters")
        String displayName) {
    public CreateCustomerRequest {
        if (displayName != null) { displayName = displayName.strip(); }
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown request field");
    }
}
