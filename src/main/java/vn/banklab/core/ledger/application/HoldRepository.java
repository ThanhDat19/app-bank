package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;

public interface HoldRepository {
    record Hold(UUID id, UUID accountId, UUID destinationAccountId, BigDecimal amount,
                Instant expiresAt, String state, UUID journalId) { }

    Hold claim(Actor actor, String key, UUID accountId, UUID destinationId, BigDecimal amount, Instant expiresAt);
    Optional<Hold> owned(UUID id, Actor actor, boolean lock);
    void transition(UUID id, String state, UUID journalId);
    BigDecimal reserved(UUID accountId);
    Instant now();
}
