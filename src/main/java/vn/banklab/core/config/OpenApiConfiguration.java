package vn.banklab.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.banklab.core.shared.web.CorrelationIdFilter;
import vn.banklab.core.iam.infrastructure.security.IamProperties;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI bankingOpenApi(IamProperties iam) {
        var flow = new OAuthFlow()
                .authorizationUrl(iam.issuerUri() + "/protocol/openid-connect/auth")
                .tokenUrl(iam.issuerUri() + "/protocol/openid-connect/token")
                .scopes(new Scopes().addString("openid", "Sign in through the configured identity provider"));
        return new OpenAPI().components(new Components().addSecuritySchemes("oidc", new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2).flows(new OAuthFlows().authorizationCode(flow))))
                .addSecurityItem(new SecurityRequirement().addList("oidc", List.of("openid")))
                .info(new Info()
                .title("Bank Lab API")
                .version("v1")
                .description("Banking simulation for learning and review. Uses synthetic data only."));
    }

    @Bean
    OpenApiCustomizer correlationHeaders() {
        return api -> {
            if (api.getPaths() == null) {
                return;
            }
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (path.equals("/api/v1/system/info")) {
                    operation.setSecurity(List.of());
                }
                operation.addParametersItem(new Parameter().in("header").name(CorrelationIdFilter.HEADER)
                        .required(false).description("Optional opaque request ID. Invalid or repeated values are replaced.")
                        .schema(new StringSchema().minLength(1).maxLength(64)
                                .pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")));
                operation.getResponses().values().forEach(response -> response.addHeaderObject(
                        CorrelationIdFilter.HEADER, new Header().description("Request correlation ID")
                                .schema(new StringSchema())));
            }));
        };
    }
}
