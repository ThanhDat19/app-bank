package vn.banklab.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI bankingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Bank Lab API")
                .version("v1")
                .description("Banking simulation for learning and review. Uses synthetic data only."));
    }
}
