package vn.banklab.core.notification.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import vn.banklab.core.notification.application.*;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcClient jdbc;
    public JdbcNotificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public boolean claim(UUID id,String hash) {
        int inserted=jdbc.sql("INSERT INTO banking.notification_inbox(event_id,payload_hash) VALUES(:id,:hash) ON CONFLICT DO NOTHING")
                .param("id",id).param("hash",hash).update();
        if(inserted==0 && !jdbc.sql("SELECT payload_hash FROM banking.notification_inbox WHERE event_id=:id").param("id",id).query(String.class).single().equals(hash))
            throw new IllegalArgumentException("INVALID_EVENT");
        return inserted==1;
    }
    public void insert(Notification n) {
        jdbc.sql("INSERT INTO banking.notifications(event_id,account_id,journal_id,kind,occurred_at) VALUES(:event,:account,:journal,:kind,:time)")
                .param("event",n.eventId()).param("account",n.accountId()).param("journal",n.journalId()).param("kind",n.kind()).param("time",Timestamp.from(n.occurredAt())).update();
    }
    public void processed(SourceRecord s,UUID eventId) {
        jdbc.sql("INSERT INTO banking.notification_processed_sources(source_topic,source_partition,source_offset,event_id) VALUES(:topic,:part,:offset,:event) ON CONFLICT DO NOTHING")
                .param("topic",s.topic()).param("part",s.partition()).param("offset",s.offset()).param("event",eventId).update();
        if(!jdbc.sql("SELECT event_id FROM banking.notification_processed_sources WHERE source_topic=:topic AND source_partition=:part AND source_offset=:offset")
                .param("topic",s.topic()).param("part",s.partition()).param("offset",s.offset()).query(UUID.class).single().equals(eventId))
            throw new IllegalArgumentException("INVALID_EVENT");
    }
    public List<Notification> latest(UUID accountId,int limit) {
        return jdbc.sql("SELECT * FROM banking.notifications WHERE account_id=:id ORDER BY occurred_at DESC,event_id DESC LIMIT :limit")
                .param("id",accountId).param("limit",limit).query((r,n)->new Notification(r.getObject("event_id",UUID.class),accountId,
                        r.getObject("journal_id",UUID.class),r.getString("kind"),r.getTimestamp("occurred_at").toInstant())).list();
    }
    public long count(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM banking.notifications WHERE account_id=:id")
                .param("id",accountId).query(Long.class).single();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Failure failure(SourceRecord s) {
        return jdbc.sql("SELECT attempts,available_at<=clock_timestamp() AS ready,quarantined FROM banking.notification_failures WHERE source_topic=:topic AND source_partition=:part AND source_offset=:offset")
                .param("topic",s.topic()).param("part",s.partition()).param("offset",s.offset())
                .query((r,n)->new Failure(r.getInt("attempts"),r.getBoolean("ready"),r.getBoolean("quarantined"))).optional().orElse(new Failure(0,true,false));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Failure failed(SourceRecord s,String hash,String code) {
        return jdbc.sql("""
                INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,error_code)
                VALUES(:topic,:part,:offset,:hash,1,clock_timestamp()+interval '500 milliseconds',:code)
                ON CONFLICT(source_topic,source_partition,source_offset) DO UPDATE SET attempts=banking.notification_failures.attempts+1,
                available_at=clock_timestamp()+least(10000,power(2,least(banking.notification_failures.attempts,5))*500)*interval '1 millisecond'
                RETURNING attempts
                """).param("topic",s.topic()).param("part",s.partition()).param("offset",s.offset()).param("hash",hash).param("code",code)
                .query((r,n)->new Failure(r.getInt("attempts"),false,false)).single();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void quarantine(SourceRecord s,String hash,String code) {
        jdbc.sql("""
                INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,error_code,quarantined)
                VALUES(:topic,:part,:offset,:hash,1,clock_timestamp(),:code,true)
                ON CONFLICT(source_topic,source_partition,source_offset) DO UPDATE SET quarantined=true,error_code=:code
                """).param("topic",s.topic()).param("part",s.partition()).param("offset",s.offset()).param("hash",hash).param("code",code).update();
    }
}
