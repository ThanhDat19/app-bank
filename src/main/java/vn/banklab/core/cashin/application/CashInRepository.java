package vn.banklab.core.cashin.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;

public interface CashInRepository {
    record Claim(UUID id, String requestHash, CashInReceipt receipt) { }

    Claim claim(Actor actor, String key, String hash, UUID accountId, BigDecimal amount);

    CashInReceipt complete(UUID id, UUID journalId);

    Optional<CashInReceipt> findOwned(UUID id, Actor actor);
}
