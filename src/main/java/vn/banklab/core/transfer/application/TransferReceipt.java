package vn.banklab.core.transfer.application;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record TransferReceipt(UUID id, UUID sourceAccountId, UUID destinationAccountId, UUID journalId,
                              @Schema(type = "string", pattern = "^[1-9][0-9]{0,19}$", example = "100000") String amount,
                              String currency, String status, Instant postedAt) { }
