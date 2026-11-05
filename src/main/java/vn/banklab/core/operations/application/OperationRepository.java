package vn.banklab.core.operations.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;

public interface OperationRepository {
    record Stored(OperationRequest request, String hash, String makerIssuer, String makerSubject,
                  String checkerIssuer, String checkerSubject) {
        public boolean madeBy(Actor actor) { return makerIssuer.equals(actor.issuer()) && makerSubject.equals(actor.subject()); }
        public boolean checkedBy(Actor actor) { return actor.issuer().equals(checkerIssuer) && actor.subject().equals(checkerSubject); }
    }
    Stored claim(Actor maker, String key, String hash, String type, UUID target, Long version, String reason);
    Optional<Stored> replay(Actor maker, String key);
    Optional<Stored> find(UUID id, boolean lock);
    void begin(UUID id, Actor checker);
    OperationRequest finish(UUID id, String status, Actor actor, String decisionReason, String result);
    List<OperationRequest> list(String status, int limit);
    List<History> history(UUID id, int limit);
    record History(long version, String status, String reason, java.time.Instant occurredAt) { }
}
