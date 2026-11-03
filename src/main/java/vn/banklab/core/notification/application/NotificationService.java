package vn.banklab.core.notification.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import vn.banklab.core.outbox.application.JournalEventVerifier;
import vn.banklab.core.ledger.application.LedgerNotificationAccess;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final JournalEventVerifier verifier;
    private final LedgerNotificationAccess ledger;
    private final AccountFinancialAccess accounts;
    public NotificationService(NotificationRepository notifications,JournalEventVerifier verifier,LedgerNotificationAccess ledger,AccountFinancialAccess accounts) {
        this.notifications=notifications;this.verifier=verifier;this.ledger=ledger;this.accounts=accounts;
    }
    @Transactional
    public void accept(SourceRecord source) {
        var verified=verifier.verify(source.key(),source.value()); var e=verified.event();
        if(!notifications.claim(e.eventId(),verified.hash())) return;
        var payload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(e.payload());
        for(UUID account:ledger.bankAccounts(e.aggregateId()))
            notifications.insert(new NotificationRepository.Notification(e.eventId(),account,e.aggregateId(),payload.path("kind").asString(),e.occurredAt()));
    }
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly=true)
    public List<NotificationRepository.Notification> mine(UUID accountId,int limit) {
        accounts.owned(accountId);
        if(limit<1 || limit>100) throw new BusinessException("NOTIFICATION_LIMIT_INVALID",BusinessException.Kind.RULE_VIOLATION);
        return notifications.latest(accountId,limit);
    }
}
