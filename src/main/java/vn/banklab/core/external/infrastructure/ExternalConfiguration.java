package vn.banklab.core.external.infrastructure;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import vn.banklab.core.external.application.ExternalOptions;

@Configuration(proxyBeanMethods=false)
public class ExternalConfiguration {
    @Bean ExternalOptions externalSettings(Environment environment,
            @Value("${bank.external.enabled:false}") boolean enabled,
            @Value("${bank.external.port:9092}") int port,
            @Value("${bank.external.timeout-millis:1000}") int timeout,
            @Value("${bank.external.settlement-account:00000000-0000-0000-0000-000000000000}") UUID settlement){
        if(port<1||port>65535||timeout<100||timeout>5000)throw new IllegalArgumentException("Invalid external settings");
        if(enabled&&(!environment.acceptsProfiles(Profiles.of("local"))||settlement.equals(new UUID(0,0))))
            throw new IllegalArgumentException("External simulation requires local profile and settlement account");
        return new ExternalOptions(enabled,port,timeout,settlement);
    }
}
