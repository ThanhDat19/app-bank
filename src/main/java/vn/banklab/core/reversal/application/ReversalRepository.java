package vn.banklab.core.reversal.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;

public interface ReversalRepository {
    record Claim(UUID id, String requestHash, ReversalReceipt receipt) { }
    Claim claim(Actor actor, String key, String hash, UUID originalId, String reason);
    boolean completedForOriginal(UUID originalId);
    ReversalReceipt complete(UUID id, UUID journalId, String originalKind, BigDecimal amount);
    Optional<ReversalReceipt> findOwned(UUID id, Actor actor);
}
