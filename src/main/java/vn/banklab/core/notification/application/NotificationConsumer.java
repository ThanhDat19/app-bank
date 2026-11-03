package vn.banklab.core.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class NotificationConsumer {
    private final NotificationService service;
    private final NotificationRepository repository;
    public NotificationConsumer(NotificationService service,NotificationRepository repository) { this.service=service;this.repository=repository; }
    /** True means the source offset may be committed. All transport calls stay outside DB transactions. */
    @Transactional(propagation=Propagation.NEVER)
    public boolean process(SourceRecord source,DeadLetterPublisher dlq) throws Exception {
        var previous=repository.failure(source);
        if(previous.quarantined()) return true;
        if(!previous.ready()) return false;
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((source.value()==null?"":source.value()).getBytes(StandardCharsets.UTF_8)));
        String code="PROCESSING_FAILED";
        if(previous.attempts()<3) {
            try { service.accept(source);return true;
            } catch(IllegalArgumentException invalid) { code="INVALID_EVENT";
            } catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException transientFailure) {
                var failure=repository.failed(source,hash,code);
                if(failure.attempts()<3) return false;
            }
        }
        dlq.publish(new DeadLetterPublisher.DeadLetter(source.topic(),source.partition(),source.offset(),hash,code));
        repository.quarantine(source,hash,code);
        return true;
    }
}
