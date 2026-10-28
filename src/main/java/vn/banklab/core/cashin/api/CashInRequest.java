package vn.banklab.core.cashin.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CashInRequest(@NotNull UUID accountId,
                            @NotNull @Pattern(regexp = "[1-9][0-9]{0,19}")
                            @Schema(type = "string", example = "500000", description = "Positive integer VND, at most 20 digits")
                            @JsonDeserialize(using = CashInAmountDeserializer.class)
                            String amount,
                            @NotNull @Pattern(regexp = "VND") @Schema(example = "VND") String currency) {
}
