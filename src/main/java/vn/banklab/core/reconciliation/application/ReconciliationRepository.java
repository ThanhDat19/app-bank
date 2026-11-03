package vn.banklab.core.reconciliation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.outbox.application.OutboxEvent;

public interface ReconciliationRepository {
    record Run(UUID id,String actorIssuer,String actorSubject,String actorType,Instant snapshotAt,int graceSeconds,
               String status,long errorCount,long warningCount,Instant completedAt) { }
    record Finding(long id,String code,String severity,String resourceType,String resourceId) { }
    record RuleCount(String code,String severity,long count) { }
    record Audit(long id,String actorIssuer,String actorSubject,String actorType,String action,String resourceType,UUID resourceId,Instant occurredAt) { }
    record ProcessedEvent(OutboxEvent event,String storedHash) { }
    boolean tryLock();
    void start(UUID id,Actor actor,String actorType,int graceSeconds);
    void scan(UUID id);
    List<ProcessedEvent> hashes(UUID afterId);
    void hashMismatch(UUID runId,UUID eventId);
    void complete(UUID id);
    Optional<Run> find(UUID id);
    List<RuleCount> summary(UUID id);
    List<Finding> findings(UUID runId,long afterId,int limit);
    List<Audit> audit(long beforeId,int limit);
}
