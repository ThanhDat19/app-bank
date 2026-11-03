package vn.banklab.core.outbox.application;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface OutboxRepository {
    record Claim(OutboxEvent event, UUID token, int attempt) { }
    List<Claim> claim(int batchSize, long leaseMillis);
    boolean acknowledge(UUID eventId, UUID token);
    boolean retry(UUID eventId, UUID token, long delayMillis);
    Optional<OutboxEvent> find(UUID eventId);
}
