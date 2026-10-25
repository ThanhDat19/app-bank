package vn.banklab.core.iam.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("bank.iam")
public record IamProperties(@NotNull URI issuerUri, @NotNull URI jwkSetUri, @NotBlank String audience) {
}
