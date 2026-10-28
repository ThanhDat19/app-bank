package vn.banklab.core.cashin.application;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record CashInReceipt(UUID id, UUID accountId, UUID journalId,
                            @Schema(type = "string", pattern = "^[1-9][0-9]{0,19}$", example = "500000") String amount, String currency,
                            String status, Instant postedAt) {
}
