package vn.banklab.core.observability.infrastructure;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.observability.application.SnapshotQuery;

@Repository
public class JdbcSnapshotQuery implements SnapshotQuery {
    private final JdbcClient jdbc;
    private final int statementMillis;
    public JdbcSnapshotQuery(JdbcClient jdbc, @Value("${bank.monitoring.statement-timeout-millis:500}") int statementMillis) {
        if (statementMillis < 50 || statementMillis > 2000) throw new IllegalArgumentException("Invalid monitoring statement timeout");
        this.jdbc = jdbc; this.statementMillis = statementMillis;
    }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public Map<String, Long> read() {
        jdbc.sql("SELECT set_config('statement_timeout',:timeout,true)").param("timeout", statementMillis + "ms").query(String.class).single();
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM banking.outbox_deliveries WHERE state='PENDING') AS outbox_pending,
                  (SELECT count(*) FROM banking.outbox_deliveries WHERE state='LEASED') AS outbox_leased,
                  (SELECT COALESCE(GREATEST(0,floor(extract(epoch FROM statement_timestamp()-min(e.occurred_at)))),0)::bigint
                   FROM banking.outbox_events e JOIN banking.outbox_deliveries d USING(event_id) WHERE d.state<>'DELIVERED') AS outbox_oldest,
                  (SELECT count(*) FROM banking.notification_failures WHERE NOT quarantined) AS retries,
                  (SELECT count(*) FROM banking.notification_failures WHERE quarantined) AS quarantined,
                  (SELECT count(*) FROM banking.external_payments WHERE state='UNKNOWN') AS unknown,
                  (SELECT count(*) FROM banking.external_payments WHERE state='PREPARED') AS prepared,
                  (SELECT count(*) FROM banking.operation_requests WHERE status='PENDING') AS operations
                """).query((row,n) -> Map.of("outboxPending",row.getLong("outbox_pending"),"outboxLeased",row.getLong("outbox_leased"),
                        "outboxOldestSeconds",row.getLong("outbox_oldest"),"notificationRetries",row.getLong("retries"),
                        "notificationQuarantined",row.getLong("quarantined"),"externalUnknown",row.getLong("unknown"),
                        "externalPrepared",row.getLong("prepared"),"operationsPending",row.getLong("operations"))).single();
    }
}
