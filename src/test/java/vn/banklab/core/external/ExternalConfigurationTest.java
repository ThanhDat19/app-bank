package vn.banklab.core.external;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.banklab.core.external.application.ExternalOptions;
import vn.banklab.core.external.infrastructure.ExternalConfiguration;
import vn.banklab.core.external.infrastructure.HttpPartnerGateway;
import vn.banklab.core.shared.application.BusinessException;
import static org.assertj.core.api.Assertions.*;

class ExternalConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ExternalConfiguration.class);
    @Test void featureIsDisabledByDefaultWithoutNetworkFallback(){
        runner.run(c->{assertThat(c).hasNotFailed();var settings=c.getBean(ExternalOptions.class);assertThat(settings.enabled()).isFalse();
            assertThatThrownBy(()->new HttpPartnerGateway(settings).instance()).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EXTERNAL_DISABLED"));});
    }
    @Test void enabledRequiresLocalProfileClearingAccountAndBoundedTimeout(){
        runner.withPropertyValues("bank.external.enabled=true").run(c->assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=local","bank.external.enabled=true").run(c->assertThat(c).hasFailed());
        runner.withPropertyValues("bank.external.timeout-millis=0").run(c->assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=local","bank.external.enabled=true","bank.external.settlement-account="+UUID.randomUUID()).run(c->assertThat(c).hasNotFailed());
    }
    @Test void partnerAdapterRejectsAnyActiveTransactionBeforeIO(){
        var gateway=new HttpPartnerGateway(new ExternalOptions(true,9092,1000,UUID.randomUUID()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(gateway::instance).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
