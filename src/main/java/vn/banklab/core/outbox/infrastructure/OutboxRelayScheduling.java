package vn.banklab.core.outbox.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.banklab.core.outbox.application.OutboxPublisher;
import vn.banklab.core.outbox.application.OutboxRelay;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="bank.outbox.relay.enabled",havingValue="true")
public class OutboxRelayScheduling {
    private final OutboxRelay relay;
    private final OutboxPublisher publisher;
    private final int batchSize;
    private final long leaseMillis;

    public OutboxRelayScheduling(OutboxRelay relay,OutboxPublisher publisher,
            @Value("${bank.outbox.relay.batch-size:20}") int batchSize,
            @Value("${bank.outbox.relay.lease-millis:30000}") long leaseMillis) {
        if(batchSize<1 || batchSize>100 || leaseMillis<1 || leaseMillis>300000) throw new IllegalArgumentException("Invalid outbox configuration");
        this.relay=relay; this.publisher=publisher; this.batchSize=batchSize; this.leaseMillis=leaseMillis;
    }

    @Scheduled(fixedDelayString="${bank.outbox.relay.poll-millis:1000}")
    public void poll() { relay.deliverBatch(publisher,batchSize,leaseMillis); }
}
