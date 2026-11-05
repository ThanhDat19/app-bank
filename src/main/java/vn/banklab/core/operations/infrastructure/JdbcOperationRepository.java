package vn.banklab.core.operations.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.operations.application.OperationRepository;
import vn.banklab.core.operations.application.OperationRequest;
import vn.banklab.core.shared.application.BusinessException;
import vn.banklab.core.shared.application.OperationApprovalGuard;

@Repository
public class JdbcOperationRepository implements OperationRepository, OperationApprovalGuard {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CurrentActorProvider actors;
    public JdbcOperationRepository(JdbcClient jdbc, JsonMapper json, CurrentActorProvider actors) {
        this.jdbc = jdbc; this.json = json; this.actors = actors;
    }
    @Override public Optional<Stored> replay(Actor actor, String key) {
        return jdbc.sql("SELECT * FROM banking.operation_requests WHERE maker_issuer=:issuer AND maker_subject=:subject AND idempotency_key=:key")
                .param("issuer", actor.issuer()).param("subject", actor.subject()).param("key", key).query(this::stored).optional();
    }
    @Override public Stored claim(Actor maker, String key, String hash, String type, UUID target, Long version, String reason) {
        jdbc.sql("""
                INSERT INTO banking.operation_requests(id,maker_issuer,maker_subject,idempotency_key,request_hash,type,target_id,expected_account_version,reason)
                VALUES(:id,:issuer,:subject,:key,:hash,:type,:target,:version,:reason)
                ON CONFLICT(maker_issuer,maker_subject,idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("issuer", maker.issuer()).param("subject", maker.subject())
                .param("key", key).param("hash", hash).param("type", type).param("target", target)
                .param("version", version, Types.BIGINT).param("reason", reason).update();
        return replay(maker, key).orElseThrow();
    }
    @Override public Optional<Stored> find(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM banking.operation_requests WHERE id=:id" + (lock ? " FOR NO KEY UPDATE" : ""))
                .param("id", id).query(this::stored).optional();
    }
    @Override public void begin(UUID id, Actor actor) {
        if (jdbc.sql("""
                UPDATE banking.operation_requests SET status='EXECUTING',checker_issuer=:issuer,checker_subject=:subject,
                  execution_txid=pg_current_xact_id()::text WHERE id=:id AND status='PENDING'
                """).param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject()).update() != 1)
            throw new IllegalStateException("Locked pending request required");
    }
    @Override public OperationRequest finish(UUID id, String status, Actor actor, String reason, String result) {
        return jdbc.sql("""
                UPDATE banking.operation_requests SET status=:status,version=1,
                  checker_issuer=CASE WHEN :status='CANCELLED' THEN NULL ELSE :issuer END,
                  checker_subject=CASE WHEN :status='CANCELLED' THEN NULL ELSE :subject END,
                  decision_reason=:reason,result=CAST(:result AS jsonb),completed_at=clock_timestamp()
                WHERE id=:id RETURNING *
                """).param("id", id).param("status", status).param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("reason", reason).param("result", result, Types.VARCHAR).query((row, n) -> request(row)).single();
    }
    @Override public List<OperationRequest> list(String status, int limit) {
        return jdbc.sql("SELECT * FROM banking.operation_requests WHERE status=:status ORDER BY created_at DESC,id DESC LIMIT :limit")
                .param("status", status).param("limit", limit).query((row, n) -> request(row)).list();
    }
    @Override public List<History> history(UUID id, int limit) {
        return jdbc.sql("SELECT * FROM banking.operation_history WHERE request_id=:id ORDER BY version DESC LIMIT :limit")
                .param("id", id).param("limit", limit).query((row, n) -> new History(row.getLong("version"), row.getString("status"),
                        row.getString("reason"), row.getTimestamp("occurred_at").toInstant())).list();
    }
    @Override public void require(String type, UUID target, Long version, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) denied();
        var actor = actors.currentActor();
        boolean allowed = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM banking.operation_requests WHERE status='EXECUTING'
                  AND execution_txid=pg_current_xact_id()::text AND type=:type AND target_id=:target
                  AND expected_account_version IS NOT DISTINCT FROM CAST(:version AS bigint) AND reason=:reason
                  AND checker_issuer=:issuer AND checker_subject=:subject)
                """).param("type", type).param("target", target, Types.OTHER).param("version", version, Types.BIGINT)
                .param("reason", reason, Types.VARCHAR).param("issuer", actor.issuer()).param("subject", actor.subject()).query(Boolean.class).single();
        if (!allowed) denied();
    }
    private static void denied() { throw new BusinessException("OPERATION_APPROVAL_REQUIRED", BusinessException.Kind.CONFLICT); }
    private Stored stored(ResultSet row, int n) throws SQLException {
        return new Stored(request(row), row.getString("request_hash"), row.getString("maker_issuer"), row.getString("maker_subject"),
                row.getString("checker_issuer"), row.getString("checker_subject"));
    }
    private OperationRequest request(ResultSet r) throws SQLException {
        String result = r.getString("result"); var completed = r.getTimestamp("completed_at");
        return new OperationRequest(r.getObject("id", UUID.class), r.getString("type"), r.getObject("target_id", UUID.class),
                (Long) r.getObject("expected_account_version"), r.getString("reason"), r.getString("status"), r.getLong("version"),
                r.getTimestamp("created_at").toInstant(), completed == null ? null : completed.toInstant(), r.getString("decision_reason"),
                result == null ? null : json.readTree(result));
    }
}
