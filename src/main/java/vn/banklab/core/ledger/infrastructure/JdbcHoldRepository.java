package vn.banklab.core.ledger.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.ledger.application.HoldRepository;

@Repository
public class JdbcHoldRepository implements HoldRepository {
    private final JdbcClient jdbc;
    private static final RowMapper<Hold> MAPPER = (r, n) -> new Hold(r.getObject("id", UUID.class),
            r.getObject("account_id", UUID.class), r.getObject("destination_account_id", UUID.class),
            r.getBigDecimal("amount"), r.getTimestamp("expires_at").toInstant(), r.getString("state"),
            r.getObject("journal_id", UUID.class));

    public JdbcHoldRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Hold claim(Actor actor, String key, UUID accountId, UUID destinationId, BigDecimal amount, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO banking.holds(id,actor_issuer,actor_subject,idempotency_key,account_id,destination_account_id,amount,expires_at,state)
                VALUES(:id,:issuer,:subject,:key,:account,:destination,:amount,:expiry,'PENDING')
                ON CONFLICT(actor_issuer,actor_subject,idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("key", key).param("account", accountId).param("destination", destinationId)
                .param("amount", amount).param("expiry", Timestamp.from(expiresAt)).update();
        return jdbc.sql("SELECT * FROM banking.holds WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key FOR UPDATE")
                .param("issuer", actor.issuer()).param("subject", actor.subject()).param("key", key).query(MAPPER).single();
    }

    public Optional<Hold> owned(UUID id, Actor actor, boolean lock) {
        return jdbc.sql("SELECT * FROM banking.holds WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("issuer", actor.issuer()).param("subject", actor.subject()).query(MAPPER).optional();
    }

    public void transition(UUID id, String state, UUID journalId) {
        jdbc.sql("UPDATE banking.holds SET state=:state,journal_id=:journal WHERE id=:id")
                .param("state", state).param("journal", journalId).param("id", id).update();
    }

    public BigDecimal reserved(UUID accountId) {
        return jdbc.sql("SELECT banking.reserved_balance(:id)").param("id", accountId).query(BigDecimal.class).single();
    }

    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }
}
