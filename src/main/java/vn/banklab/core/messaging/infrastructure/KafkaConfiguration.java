package vn.banklab.core.messaging.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.banklab.core.notification.application.NotificationConsumer;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="bank.kafka.enabled",havingValue="true")
public class KafkaConfiguration {
    @Bean(destroyMethod="close")
    KafkaTransport kafkaTransport(@Value("${bank.kafka.bootstrap-servers}") String bootstrap,
            @Value("${bank.kafka.events-topic:bank.ledger.journal-posted.v1}") String topic,
            @Value("${bank.kafka.dlq-topic:bank.notifications.dlq.v1}") String dlq) { return new KafkaTransport(bootstrap,topic,dlq); }

    @Bean(destroyMethod="close")
    KafkaNotificationWorker kafkaNotificationWorker(@Value("${bank.kafka.bootstrap-servers}") String bootstrap,
            @Value("${bank.kafka.notification-group:bank-notifications-v1}") String group,
            @Value("${bank.kafka.events-topic:bank.ledger.journal-posted.v1}") String topic,
            NotificationConsumer consumer,KafkaTransport transport) { return new KafkaNotificationWorker(bootstrap,group,topic,consumer,transport); }

    @Bean NotificationPolling notificationPolling(KafkaNotificationWorker worker) { return new NotificationPolling(worker); }

    public static class NotificationPolling {
        private final KafkaNotificationWorker worker;
        public NotificationPolling(KafkaNotificationWorker worker) { this.worker=worker; }
        @Scheduled(fixedDelayString="${bank.kafka.notification-poll-millis:500}")
        public void poll() throws Exception { worker.pollOnce(); }
    }
}
