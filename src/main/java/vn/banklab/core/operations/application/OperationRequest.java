package vn.banklab.core.operations.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Public operational receipt. Raw identity, keys and hashes stay in persistence. */
public record OperationRequest(UUID id, String type, UUID targetId, Long expectedAccountVersion, String reason,
                               String status, long version, Instant createdAt, Instant completedAt,
                               String decisionReason, JsonNode result) { }
