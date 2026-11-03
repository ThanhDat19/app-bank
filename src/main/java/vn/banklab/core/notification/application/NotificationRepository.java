package vn.banklab.core.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository {
    record Notification(UUID eventId,UUID accountId,UUID journalId,String kind,Instant occurredAt) { }
    record Failure(int attempts,boolean ready,boolean quarantined) { }
    boolean claim(UUID eventId,String hash);
    void processed(SourceRecord source,UUID eventId);
    void insert(Notification notification);
    List<Notification> latest(UUID accountId,int limit);
    long count(UUID accountId);
    Failure failure(SourceRecord source);
    Failure failed(SourceRecord source,String hash,String code);
    void quarantine(SourceRecord source,String hash,String code);
}
