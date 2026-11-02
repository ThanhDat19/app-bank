package vn.banklab.core.transfer.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

public record TransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
                              @NotNull @Pattern(regexp = "[1-9][0-9]{0,19}")
                              @Schema(type = "string", example = "100000", description = "Positive integer VND, at most 20 digits")
                              @JsonDeserialize(using = TransferAmountDeserializer.class) String amount,
                              @NotNull @Pattern(regexp = "VND") @Schema(example = "VND") String currency) { }
