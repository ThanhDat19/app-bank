package vn.banklab.core.system.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public simulation metadata; contains no infrastructure or customer details")
public record SystemInfoResponse(
        @Schema(example = "core-banking-service", requiredMode = Schema.RequiredMode.REQUIRED)
        String service,
        @Schema(example = "SIMULATION", allowableValues = "SIMULATION",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String mode) {
}
