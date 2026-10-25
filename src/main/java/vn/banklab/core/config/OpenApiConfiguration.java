package vn.banklab.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.banklab.core.shared.web.CorrelationIdFilter;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI bankingOpenApi() {
        return new OpenAPI().info(new Info()
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
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                operation.addParametersItem(new Parameter().in("header").name(CorrelationIdFilter.HEADER)
                        .required(false).description("Optional opaque request ID. Invalid or repeated values are replaced.")
                        .schema(new StringSchema().minLength(1).maxLength(64)
                                .pattern("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")));
                operation.getResponses().values().forEach(response -> response.addHeaderObject(
                        CorrelationIdFilter.HEADER, new Header().description("Request correlation ID")
                                .schema(new StringSchema())));
            }));
        };
    }
}
