package vn.banklab.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import vn.banklab.core.iam.infrastructure.security.BankJwtAuthorities;
import vn.banklab.core.iam.infrastructure.security.IamProperties;
import vn.banklab.core.shared.web.SecurityProblemHandler;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, IamProperties properties,
                                   SecurityProblemHandler problems, Environment environment) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new BankJwtAuthorities(properties.audience()));
        String[] docs = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml"};
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                            "/actuator/health/readiness", "/api/v1/system/info").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("local"))) {
                        authorize.requestMatchers(HttpMethod.GET, docs).permitAll();
                    } else {
                        authorize.requestMatchers(docs).hasRole("OPERATOR");
                    }
                    authorize.requestMatchers("/api/v1/operations/**").hasRole("OPERATOR")
                            .requestMatchers("/api/v1/**").hasAnyRole("CUSTOMER", "OPERATOR")
                            .anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)
                        .jwtAuthenticationConverter(converter)).authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }
}
