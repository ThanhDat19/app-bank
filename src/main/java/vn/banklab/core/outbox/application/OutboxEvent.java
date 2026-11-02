package vn.banklab.core.outbox.application;

import java.time.Instant;
import java.util.UUID;

/** Transport-neutral envelope. Payload contains no identity, JWT, reason or customer PII. */
public record OutboxEvent(UUID eventId, UUID aggregateId, String aggregateType, long aggregateVersion,
                          String eventType, int schemaVersion, Instant occurredAt, String payload) { }
