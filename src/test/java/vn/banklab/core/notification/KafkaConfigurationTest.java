package vn.banklab.core.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.banklab.core.messaging.infrastructure.KafkaConfiguration;
import vn.banklab.core.messaging.infrastructure.KafkaTransport;
import vn.banklab.core.messaging.infrastructure.KafkaNotificationWorker;
import static org.assertj.core.api.Assertions.*;

class KafkaConfigurationTest {
    @Test void defaultDoesNotCreateNetworkClients() {
        new ApplicationContextRunner().withUserConfiguration(KafkaConfiguration.class).run(context->{
            assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(KafkaTransport.class);assertThat(context).doesNotHaveBean(KafkaNotificationWorker.class);
        });
    }
    @Test void producerRejectsAmbiguousDestinationBeforeConnecting() {
        assertThatThrownBy(()->new KafkaTransport("localhost:9092","same","same")).isInstanceOf(IllegalArgumentException.class);
    }
}
