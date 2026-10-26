package vn.banklab.core.ledger.api;

import java.util.UUID;

import vn.banklab.core.ledger.application.LedgerRepository;

public record LedgerAccountResponse(UUID accountId, UUID ledgerAccountId, String currency, String accountType,
                                    String normalSide) {
    static LedgerAccountResponse from(LedgerRepository.LedgerAccount account) {
        return new LedgerAccountResponse(account.bankAccountId(), account.id(), account.currency(), account.accountType(), account.normalSide());
    }
}
