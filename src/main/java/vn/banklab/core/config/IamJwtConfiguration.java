package vn.banklab.core.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;
import vn.banklab.core.iam.infrastructure.security.IamProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IamProperties.class)
public class IamJwtConfiguration {

    @Bean
    JwtDecoder jwtDecoder(IamProperties properties, Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        validateEndpoint(properties.issuerUri(), local);
        validateEndpoint(properties.jwkSetUri(), local);
        var http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(2));
        http.setReadTimeout(Duration.ofSeconds(2));
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(http)).build();
        OAuth2TokenValidator<Jwt> accessToken = token -> {
            Object subject = token.getClaims().get("sub");
            boolean valid = subject instanceof String value && !value.isBlank() && value.length() <= 255
                    && token.getExpiresAt() != null && "Bearer".equals(token.getClaims().get("typ"))
                    && token.getAudience() != null && token.getAudience().contains(properties.audience());
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Access token requirements were not met", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri().toString()), accessToken));
        return decoder;
    }

    private static void validateEndpoint(URI uri, boolean local) {
        boolean transport = "https".equalsIgnoreCase(uri.getScheme())
                || (local && "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
        if (!transport || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalStateException("IAM endpoints require HTTPS; local permits HTTP on loopback only");
        }
    }
}
