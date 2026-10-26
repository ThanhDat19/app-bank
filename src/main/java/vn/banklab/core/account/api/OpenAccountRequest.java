package vn.banklab.core.account.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @NotNull @Pattern(regexp = "VND") @Schema(example = "VND", allowableValues = "VND") String currency) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown request field");
    }
}
