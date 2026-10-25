package vn.banklab.core.iam.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Verified caller identity; no token, password or email is returned")
public record CurrentActorResponse(
        @Schema(example = "http://localhost:8180/realms/bank-lab") String issuer,
        @Schema(description = "Opaque subject from the verified token", example = "1d5e085e-4f3a-4c2b-b2dc-915b16b5ca10") String subject,
        @Schema(example = "[\"CUSTOMER\"]") List<String> roles) {
}
