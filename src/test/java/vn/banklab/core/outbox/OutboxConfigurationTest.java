package vn.banklab.core.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.banklab.core.outbox.application.OutboxRelay;
import vn.banklab.core.outbox.infrastructure.OutboxRelayScheduling;
import static org.assertj.core.api.Assertions.*;

class OutboxConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(OutboxRelayScheduling.class);
    @Test void schedulingIsDisabledByDefault() {
        runner.run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(OutboxRelayScheduling.class);});
    }
    @Test void enablingWithoutPublisherFailsClosed() {
        runner.withBean(OutboxRelay.class,()->new OutboxRelay(null)).withPropertyValues("bank.outbox.relay.enabled=true")
                .run(context->{
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
                });
    }
}
