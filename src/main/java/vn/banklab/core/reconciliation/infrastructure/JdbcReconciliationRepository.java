package vn.banklab.core.reconciliation.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.outbox.application.OutboxEvent;
import vn.banklab.core.reconciliation.application.ReconciliationRepository;

/** Diagnostic reads intentionally span consistency-boundary tables; writes only reports/findings. */
@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {
    private final JdbcClient jdbc;
    public JdbcReconciliationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public boolean tryLock() { return jdbc.sql("SELECT pg_try_advisory_xact_lock(1414,1)").query(Boolean.class).single(); }
    public void start(UUID id,Actor actor,String type,int grace) {
        jdbc.sql("""
                INSERT INTO banking.reconciliation_runs(id,actor_issuer,actor_subject,actor_type,snapshot_at,grace_seconds,status)
                VALUES(:id,:issuer,:subject,:type,transaction_timestamp(),:grace,'RUNNING')
                """).param("id",id).param("issuer",actor.issuer()).param("subject",actor.subject()).param("type",type).param("grace",grace).update();
    }
    public void scan(UUID id) {
        jdbc.sql("""
                INSERT INTO banking.reconciliation_findings(run_id,code,severity,resource_type,resource_id)
                SELECT :id,f.code,f.severity,f.resource_type,f.resource_id FROM banking.reconciliation_runs r
                CROSS JOIN LATERAL banking.reconciliation_scan(r.snapshot_at,r.grace_seconds) f
                WHERE r.id=:id ORDER BY f.code,f.resource_type,f.resource_id
                """).param("id",id).update();
    }
    public List<ProcessedEvent> hashes(UUID after) {
        return jdbc.sql("""
                SELECT e.*,i.payload_hash FROM banking.outbox_events e JOIN banking.notification_inbox i USING(event_id)
                WHERE CAST(:after AS UUID) IS NULL OR e.event_id>CAST(:after AS UUID) ORDER BY e.event_id LIMIT 500
                """).param("after",after).query((r,n)->new ProcessedEvent(new OutboxEvent(r.getObject("event_id",UUID.class),
                        r.getObject("aggregate_id",UUID.class),r.getString("aggregate_type"),r.getLong("aggregate_version"),r.getString("event_type"),
                        r.getInt("schema_version"),r.getTimestamp("occurred_at").toInstant(),r.getString("payload")),r.getString("payload_hash"))).list();
    }
    public void hashMismatch(UUID runId,UUID eventId) {
        jdbc.sql("INSERT INTO banking.reconciliation_findings(run_id,code,severity,resource_type,resource_id) VALUES(:run,'INBOX_HASH_MISMATCH','ERROR','OUTBOX_EVENT',:event)")
                .param("run",runId).param("event",eventId.toString()).update();
    }
    public void complete(UUID id) {
        jdbc.sql("""
                WITH totals AS(SELECT count(*) FILTER(WHERE severity='ERROR') errors,count(*) FILTER(WHERE severity='WARNING') warnings
                    FROM banking.reconciliation_findings WHERE run_id=:id)
                UPDATE banking.reconciliation_runs SET status=CASE WHEN t.errors>0 THEN 'MISMATCH' WHEN t.warnings>0 THEN 'WARNING' ELSE 'CLEAN' END,
                    error_count=t.errors,warning_count=t.warnings,completed_at=clock_timestamp()
                FROM totals t WHERE id=:id AND status='RUNNING'
                """).param("id",id).update();
    }
    public Optional<Run> find(UUID id) {
        return jdbc.sql("SELECT * FROM banking.reconciliation_runs WHERE id=:id").param("id",id).query((r,n)->new Run(id,
                r.getString("actor_issuer"),r.getString("actor_subject"),r.getString("actor_type"),r.getTimestamp("snapshot_at").toInstant(),
                r.getInt("grace_seconds"),r.getString("status"),r.getLong("error_count"),r.getLong("warning_count"),
                r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant())).optional();
    }
    public List<RuleCount> summary(UUID id) {
        return jdbc.sql("SELECT code,severity,count(*) total FROM banking.reconciliation_findings WHERE run_id=:id GROUP BY code,severity ORDER BY code,severity")
                .param("id",id).query((r,n)->new RuleCount(r.getString("code"),r.getString("severity"),r.getLong("total"))).list();
    }
    public List<Finding> findings(UUID id,long after,int limit) {
        return jdbc.sql("SELECT * FROM banking.reconciliation_findings WHERE run_id=:id AND id>:after ORDER BY id LIMIT :limit")
                .param("id",id).param("after",after).param("limit",limit).query((r,n)->new Finding(r.getLong("id"),r.getString("code"),
                        r.getString("severity"),r.getString("resource_type"),r.getString("resource_id"))).list();
    }
    public List<Audit> audit(long before,int limit) {
        return jdbc.sql("SELECT * FROM banking.audit_events WHERE id<:before ORDER BY id DESC LIMIT :limit")
                .param("before",before).param("limit",limit).query((r,n)->new Audit(r.getLong("id"),r.getString("actor_issuer"),
                        r.getString("actor_subject"),r.getString("actor_type"),r.getString("action"),r.getString("resource_type"),
                        r.getObject("resource_id",UUID.class),r.getTimestamp("occurred_at").toInstant())).list();
    }
}
