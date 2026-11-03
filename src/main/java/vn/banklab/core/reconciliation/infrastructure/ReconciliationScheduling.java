package vn.banklab.core.reconciliation.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.banklab.core.reconciliation.application.ReconciliationService;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="bank.reconciliation.enabled",havingValue="true")
public class ReconciliationScheduling {
    private final ReconciliationService service;
    public ReconciliationScheduling(ReconciliationService service) {this.service=service;}
    @Scheduled(fixedDelayString="${bank.reconciliation.poll-millis:3600000}")
    public void run() {service.runScheduled();}
}
