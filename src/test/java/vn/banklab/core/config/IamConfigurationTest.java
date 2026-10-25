package vn.banklab.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class IamConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IamJwtConfiguration.class)
            .withPropertyValues("bank.iam.issuer-uri=https://idp.invalid/issuer",
                    "bank.iam.jwk-set-uri=https://idp.invalid/jwks", "bank.iam.audience=bank-api");

    @Test
    void startsWithoutContactingIdpButStillRequiresTrustConfiguration() {
        context.run(application -> assertThat(application).hasNotFailed().hasSingleBean(JwtDecoder.class));
    }

    @Test
    void rejectsHttpOutsideLocal() {
        context.withPropertyValues("bank.iam.issuer-uri=http://localhost:8180/issuer")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void rejectsNonLoopbackHttpEvenInLocal() {
        context.withPropertyValues("spring.profiles.active=local", "bank.iam.jwk-set-uri=http://example.invalid/jwks")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void rejectsEmptyAudience() {
        context.withPropertyValues("bank.iam.audience=")
                .run(application -> assertThat(application).hasFailed());
    }
}
