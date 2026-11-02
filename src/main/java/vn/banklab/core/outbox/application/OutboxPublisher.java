package vn.banklab.core.outbox.application;

/** Return only after durable transport acceptance. Consumers must deduplicate eventId. */
@FunctionalInterface
public interface OutboxPublisher {
    void publish(OutboxEvent event) throws Exception;
}
