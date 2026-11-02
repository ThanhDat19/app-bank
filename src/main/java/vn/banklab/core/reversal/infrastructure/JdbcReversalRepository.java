package vn.banklab.core.reversal.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.reversal.application.ReversalReceipt;
import vn.banklab.core.reversal.application.ReversalRepository;

@Repository
public class JdbcReversalRepository implements ReversalRepository {
    private final JdbcClient jdbc;
    public JdbcReversalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Claim claim(Actor actor, String key, String hash, UUID originalId, String reason) {
        jdbc.sql("""
                INSERT INTO banking.reversals (id,actor_issuer,actor_subject,idempotency_key,request_hash,original_journal_id,reason)
                VALUES (:id,:issuer,:subject,:key,:hash,:original,:reason)
                ON CONFLICT (actor_issuer,actor_subject,idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("key", key).param("hash", hash).param("original", originalId).param("reason", reason).update();
        return jdbc.sql("""
                SELECT * FROM banking.reversals
                WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key FOR UPDATE
                """).param("issuer", actor.issuer()).param("subject", actor.subject()).param("key", key)
                .query((row, index) -> new Claim(row.getObject("id", UUID.class), row.getString("request_hash"),
                        row.getObject("journal_id") == null ? null : receipt(row))).single();
    }

    @Override
    public boolean completedForOriginal(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM banking.reversals WHERE original_journal_id=:id AND journal_id IS NOT NULL)")
                .param("id", id).query(Boolean.class).single();
    }

    @Override
    public ReversalReceipt complete(UUID id, UUID journalId, String kind, BigDecimal amount) {
        return jdbc.sql("""
                UPDATE banking.reversals SET journal_id=:journal,original_kind=:kind,amount=:amount,
                    posted_at=(SELECT posted_at FROM banking.ledger_journals WHERE id=:journal)
                WHERE id=:id AND journal_id IS NULL RETURNING *
                """).param("id", id).param("journal", journalId).param("kind", kind).param("amount", amount)
                .query((row, index) -> receipt(row)).single();
    }

    @Override
    public Optional<ReversalReceipt> findOwned(UUID id, Actor actor) {
        return jdbc.sql("SELECT * FROM banking.reversals WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject AND journal_id IS NOT NULL")
                .param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject())
                .query((row, index) -> receipt(row)).optional();
    }

    private static ReversalReceipt receipt(ResultSet row) throws SQLException {
        return new ReversalReceipt(row.getObject("id", UUID.class), row.getObject("original_journal_id", UUID.class),
                row.getObject("journal_id", UUID.class), row.getString("original_kind"), row.getBigDecimal("amount").setScale(0).toPlainString(),
                "VND", row.getString("reason"), "POSTED", row.getTimestamp("posted_at").toInstant());
    }
}
