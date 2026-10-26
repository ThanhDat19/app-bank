package vn.banklab.core.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.AccountStatus;

@Schema(description = "Account lifecycle metadata; no balance or payment capability is implied")
public record AccountResponse(UUID id, @Schema(example = "100000000000") String accountNumber,
                              String currency, AccountStatus status, long version, Instant createdAt,
                              Instant updatedAt) {
    static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.accountNumber(), account.currency(), account.status(),
                account.version(), account.createdAt(), account.updatedAt());
    }
}
