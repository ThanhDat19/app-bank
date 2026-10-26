package vn.banklab.core.ledger.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import vn.banklab.core.ledger.application.LedgerQueryService;

public record LedgerBalanceResponse(UUID accountId, String currency,
                                    @Schema(description = "Exact integer VND minor units as a decimal string; avoids JavaScript precision loss",
                                            example = "500000", pattern = "^(0|[1-9][0-9]{0,19})$") String postedBalance) {
    static LedgerBalanceResponse from(LedgerQueryService.Balance balance) {
        return new LedgerBalanceResponse(balance.accountId(), balance.currency(), balance.postedBalance().setScale(0).toPlainString());
    }
}
