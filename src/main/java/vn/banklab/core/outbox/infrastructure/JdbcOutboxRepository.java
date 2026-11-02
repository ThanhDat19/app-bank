package vn.banklab.core.outbox.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.outbox.application.OutboxEvent;
import vn.banklab.core.outbox.application.OutboxRepository;

@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcClient jdbc;
    public JdbcOutboxRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override
    public List<Claim> claim(int batchSize, long leaseMillis) {
        if(batchSize<1 || batchSize>100 || leaseMillis<1 || leaseMillis>300000) throw new IllegalArgumentException("Invalid outbox batch or lease");
        return jdbc.sql("""
                WITH ready AS (
                    SELECT event_id FROM banking.outbox_deliveries
                    WHERE (state='PENDING' AND available_at<=clock_timestamp()) OR (state='LEASED' AND lease_until<=clock_timestamp())
                    ORDER BY available_at,event_id FOR UPDATE SKIP LOCKED LIMIT :batch
                ), claimed AS (
                    UPDATE banking.outbox_deliveries d SET state='LEASED',attempts=attempts+1,
                        claim_token=gen_random_uuid(),lease_until=clock_timestamp()+(:lease * interval '1 millisecond')
                    FROM ready r WHERE d.event_id=r.event_id RETURNING d.*
                )
                SELECT e.*,c.claim_token,c.attempts FROM claimed c JOIN banking.outbox_events e USING(event_id)
                """).param("batch",batchSize).param("lease",leaseMillis).query((r,n)->new Claim(new OutboxEvent(
                        r.getObject("event_id",UUID.class),r.getObject("aggregate_id",UUID.class),r.getString("aggregate_type"),
                        r.getLong("aggregate_version"),r.getString("event_type"),r.getInt("schema_version"),
                        r.getTimestamp("occurred_at").toInstant(),r.getString("payload")),r.getObject("claim_token",UUID.class),r.getInt("attempts"))).list();
    }

    @Override
    public boolean acknowledge(UUID id,UUID token) {
        return jdbc.sql("""
                UPDATE banking.outbox_deliveries SET state='DELIVERED',delivered_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                WHERE event_id=:id AND claim_token=:token AND state='LEASED' AND lease_until>clock_timestamp()
                """).param("id",id).param("token",token).update()==1;
    }

    @Override
    public boolean retry(UUID id,UUID token,long delayMillis) {
        if(delayMillis<1 || delayMillis>300000) throw new IllegalArgumentException("Invalid outbox retry delay");
        return jdbc.sql("""
                UPDATE banking.outbox_deliveries SET state='PENDING',available_at=clock_timestamp()+(:delay * interval '1 millisecond'),
                    claim_token=NULL,lease_until=NULL WHERE event_id=:id AND claim_token=:token AND state='LEASED' AND lease_until>clock_timestamp()
                """).param("id",id).param("token",token).param("delay",delayMillis).update()==1;
    }
}
