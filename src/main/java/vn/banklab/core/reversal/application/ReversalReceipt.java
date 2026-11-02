package vn.banklab.core.reversal.application;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record ReversalReceipt(UUID id, UUID originalJournalId, UUID journalId, String originalKind,
                              @Schema(type = "string", pattern = "^[1-9][0-9]{0,19}$") String amount,
                              String currency, String reason, String status, Instant postedAt) { }
