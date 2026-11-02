package vn.banklab.core.reversal.api;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReversalRequest(@NotNull UUID originalJournalId,
                              @NotNull @Pattern(regexp = "OPERATOR_ERROR|DUPLICATE_PROCESSING")
                              @Schema(allowableValues = {"OPERATOR_ERROR", "DUPLICATE_PROCESSING"}) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown reversal request field");
    }
}
