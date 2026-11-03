package vn.banklab.core.risk;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.risk.infrastructure.RiskConfiguration;
import io.grpc.ManagedChannel;
import static org.assertj.core.api.Assertions.*;

class RiskConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(RiskConfiguration.class);
    @Test void disabledByDefaultIsExplicitAndNeverAllowsRiskWorkInsideTransaction(){
        runner.run(c->{
            assertThat(c).hasNotFailed();var gate=c.getBean(TransferRiskGate.class);
            var decision=gate.assess("a".repeat(64),"1");assertThat(decision.outcome()).isEqualTo("BYPASSED");assertThat(decision.expiresAt()).isNull();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{assertThatThrownBy(()->gate.assess("a".repeat(64),"1")).isInstanceOf(IllegalStateException.class);}
            finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        });
    }
    @Test void insecureRemoteAndInvalidLimitsFailStartup(){
        runner.withPropertyValues("bank.risk.enabled=true").run(c->assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=local","bank.risk.enabled=true","bank.risk.host=192.0.2.1").run(c->assertThat(c).hasFailed());
        runner.withPropertyValues("bank.risk.deadline-millis=0").run(c->assertThat(c).hasFailed());
    }
    @Test void enabledLocalChannelClosesWithContext(){
        var channel=new java.util.concurrent.atomic.AtomicReference<ManagedChannel>();
        runner.withPropertyValues("spring.profiles.active=local","bank.risk.enabled=true").run(c->{
            assertThat(c).hasNotFailed();channel.set((ManagedChannel)ReflectionTestUtils.getField(c.getBean(TransferRiskGate.class),"channel"));
            assertThat(channel.get().isShutdown()).isFalse();
        });
        assertThat(channel.get().isShutdown()).isTrue();
    }
}
