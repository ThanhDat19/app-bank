package vn.banklab.core.cashin.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.cashin.application.CashInReceipt;
import vn.banklab.core.cashin.application.CashInRepository;
import vn.banklab.core.iam.application.Actor;

@Repository
public class JdbcCashInRepository implements CashInRepository {
    private final JdbcClient jdbc;

    public JdbcCashInRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(Actor actor, String key, String hash, UUID accountId, BigDecimal amount) {
        jdbc.sql("""
                INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount)
                VALUES (:id,:issuer,:subject,:key,:hash,:account,:amount)
                ON CONFLICT (actor_issuer,actor_subject,idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("key", key).param("hash", hash).param("account", accountId).param("amount", amount).update();
        return jdbc.sql("""
                SELECT * FROM banking.cash_ins
                WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key FOR UPDATE
                """).param("issuer", actor.issuer()).param("subject", actor.subject()).param("key", key)
                .query((row, index) -> new Claim(row.getObject("id", UUID.class), row.getString("request_hash"),
                        row.getObject("journal_id") == null ? null : receipt(row))).single();
    }

    @Override
    public CashInReceipt complete(UUID id, UUID journalId) {
        return jdbc.sql("""
                UPDATE banking.cash_ins SET journal_id=:journal,
                    posted_at=(SELECT posted_at FROM banking.ledger_journals WHERE id=:journal)
                WHERE id=:id AND journal_id IS NULL RETURNING *
                """).param("id", id).param("journal", journalId).query((row, index) -> receipt(row)).single();
    }

    @Override
    public Optional<CashInReceipt> findOwned(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT * FROM banking.cash_ins
                WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject AND journal_id IS NOT NULL
                """).param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject())
                .query((row, index) -> receipt(row)).optional();
    }

    private static CashInReceipt receipt(ResultSet row) throws SQLException {
        return new CashInReceipt(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
                row.getObject("journal_id", UUID.class), row.getBigDecimal("amount").setScale(0).toPlainString(),
                "VND", "POSTED", row.getTimestamp("posted_at").toInstant());
    }
}
