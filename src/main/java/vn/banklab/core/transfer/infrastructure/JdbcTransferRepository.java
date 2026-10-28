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
    public Optional<TransferReceipt> findOwned(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT * FROM banking.transfers
                WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject AND journal_id IS NOT NULL
                """).param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject())
                .query((row, index) -> receipt(row)).optional();
    }

    private static TransferReceipt receipt(ResultSet row) throws SQLException {
        return new TransferReceipt(row.getObject("id", UUID.class), row.getObject("source_account_id", UUID.class),
                row.getObject("destination_account_id", UUID.class), row.getObject("journal_id", UUID.class),
                row.getBigDecimal("amount").setScale(0).toPlainString(), "VND", "POSTED", row.getTimestamp("posted_at").toInstant());
    }
}
