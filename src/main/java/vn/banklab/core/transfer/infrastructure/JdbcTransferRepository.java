package vn.banklab.core.transfer.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.transfer.application.TransferReceipt;
import vn.banklab.core.transfer.application.TransferRepository;
import vn.banklab.core.transfer.application.TransferRiskDecision;
import vn.banklab.core.risk.application.TransferRiskGate;
import java.sql.Timestamp;
import java.sql.Types;

@Repository
public class JdbcTransferRepository implements TransferRepository {
    private final JdbcClient jdbc;
    public JdbcTransferRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Claim claim(Actor actor, String key, String hash, UUID sourceId, UUID destinationId, BigDecimal amount) {
        jdbc.sql("""
                INSERT INTO banking.transfers (id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount)
                VALUES (:id,:issuer,:subject,:key,:hash,:source,:destination,:amount)
                ON CONFLICT (actor_issuer,actor_subject,idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("key", key).param("hash", hash).param("source", sourceId).param("destination", destinationId).param("amount", amount).update();
        return jdbc.sql("""
                SELECT * FROM banking.transfers
                WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key FOR UPDATE
                """).param("issuer", actor.issuer()).param("subject", actor.subject()).param("key", key)
                .query((row, index) -> new Claim(row.getObject("id", UUID.class), row.getString("request_hash"),
                        row.getObject("journal_id") == null ? null : receipt(row))).single();
    }

    @Override
    public TransferReceipt complete(UUID id, UUID journalId) {
        return jdbc.sql("""
                UPDATE banking.transfers SET journal_id=:journal,
                    posted_at=(SELECT posted_at FROM banking.ledger_journals WHERE id=:journal)
                WHERE id=:id AND journal_id IS NULL RETURNING *
                """).param("id", id).param("journal", journalId).query((row, index) -> receipt(row)).single();
    }

    @Override
    public boolean hasPostedJournal(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM banking.transfers WHERE journal_id=:id)")
                .param("id", id).query(Boolean.class).single();
    }

    @Override
    public Optional<TransferReceipt> findOwned(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT * FROM banking.transfers
                WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject AND journal_id IS NOT NULL
                """).param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject())
                .query((row, index) -> receipt(row)).optional();
    }

    public Optional<Claim> findByKey(Actor actor,String key) {
        return jdbc.sql("SELECT * FROM banking.transfers WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key")
                .param("issuer",actor.issuer()).param("subject",actor.subject()).param("key",key)
                .query((r,n)->new Claim(r.getObject("id",UUID.class),r.getString("request_hash"),r.getObject("journal_id")==null?null:receipt(r))).optional();
    }

    public void recordRisk(UUID transferId,String hash,TransferRiskGate.Approval a) {
        jdbc.sql("""
                INSERT INTO banking.transfer_risk_decisions(transfer_id,request_hash,request_id,outcome,policy_version,reason,assessed_at,expires_at)
                VALUES(:id,:hash,:request,:outcome,:policy,:reason,:assessed,:expires)
                """).param("id",transferId).param("hash",hash).param("request",a.requestId()).param("outcome",a.outcome())
                .param("policy",a.policyVersion()).param("reason",a.reason()).param("assessed",Timestamp.from(a.assessedAt()))
                .param("expires",a.expiresAt()==null?null:Timestamp.from(a.expiresAt()),Types.TIMESTAMP).update();
    }

    public Optional<TransferRiskDecision> findRiskOwned(UUID id,Actor actor) {
        return jdbc.sql("""
                SELECT d.* FROM banking.transfer_risk_decisions d JOIN banking.transfers t ON t.id=d.transfer_id
                WHERE t.id=:id AND t.actor_issuer=:issuer AND t.actor_subject=:subject
                """).param("id",id).param("issuer",actor.issuer()).param("subject",actor.subject())
                .query((r,n)->new TransferRiskDecision(id,r.getString("outcome"),r.getString("policy_version"),r.getString("reason"),
                        r.getTimestamp("assessed_at").toInstant(),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant())).optional();
    }

    private static TransferReceipt receipt(ResultSet row) throws SQLException {
        return new TransferReceipt(row.getObject("id", UUID.class), row.getObject("source_account_id", UUID.class),
                row.getObject("destination_account_id", UUID.class), row.getObject("journal_id", UUID.class),
                row.getBigDecimal("amount").setScale(0).toPlainString(), "VND", "POSTED", row.getTimestamp("posted_at").toInstant());
    }
}
