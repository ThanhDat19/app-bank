package vn.banklab.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Only MVC serialization/validation slices use this; BootstrapIT runs the real security chain. */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
public class WebContractSecurity {
    @Bean
    SecurityFilterChain webContractOnly(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }
}
