package vn.banklab.core.observability.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.banklab.core.observability.application.OperationalSnapshots;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name="bank.monitoring.scheduled",havingValue="true")
public class SnapshotScheduling {
    private final OperationalSnapshots snapshots;
    public SnapshotScheduling(OperationalSnapshots snapshots, @Value("${bank.monitoring.poll-millis:15000}") long poll) {
        if (poll < 5000 || poll > 3600000) throw new IllegalArgumentException("Invalid monitoring poll interval");
        this.snapshots = snapshots;
    }
    @Scheduled(fixedDelayString="${bank.monitoring.poll-millis:15000}")
    public void poll() { snapshots.refresh(); }
}
