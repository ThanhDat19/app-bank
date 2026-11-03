package vn.banklab.core.transfer.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.risk.application.TransferRiskGate;

public interface TransferRepository {
    record Claim(UUID id, String requestHash, TransferReceipt receipt) { }

    Claim claim(Actor actor, String key, String hash, UUID sourceId, UUID destinationId, BigDecimal amount);
    TransferReceipt complete(UUID id, UUID journalId);
    Optional<TransferReceipt> findOwned(UUID id, Actor actor);
    Optional<Claim> findByKey(Actor actor,String key);
    void recordRisk(UUID transferId,String hash,TransferRiskGate.Approval approval);
    Optional<TransferRiskDecision> findRiskOwned(UUID id,Actor actor);

    boolean hasPostedJournal(UUID journalId);
}
