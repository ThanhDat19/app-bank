package vn.banklab.core.risk.infrastructure;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.banklab.core.risk.application.TransferRiskGate;

@Configuration(proxyBeanMethods = false)
public class RiskConfiguration {
    @Bean
    TransferRiskGate transferRiskGate(Environment environment,
            @Value("${bank.risk.enabled:false}") boolean enabled,
            @Value("${bank.risk.host:127.0.0.1}") String host,
            @Value("${bank.risk.port:9091}") int port,
            @Value("${bank.risk.deadline-millis:750}") long deadline,
            @Value("${bank.risk.approval-millis:5000}") long approval) {
        if (deadline < 50 || deadline > 5000 || approval < 100 || approval > 30000
                || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid risk limits");
        if (!enabled) return (id, amount) -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Risk preflight must be outside a transaction");
            return new TransferRiskGate.Approval(id,"BYPASSED","disabled-v1","RISK_DISABLED",Instant.now(),null);
        };
        if (!environment.acceptsProfiles(Profiles.of("local")) || !"127.0.0.1".equals(host))
            throw new IllegalArgumentException("Risk simulator requires local profile and literal loopback");
        return new GrpcTransferRiskGate(host,port,deadline,approval);
    }
}
