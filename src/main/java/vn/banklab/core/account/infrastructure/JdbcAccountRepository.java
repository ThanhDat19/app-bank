package vn.banklab.core.account.infrastructure;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.account.application.AccountRepository;
import vn.banklab.core.account.domain.Account;
import vn.banklab.core.account.domain.AccountReason;
import vn.banklab.core.account.domain.AccountStatus;
import vn.banklab.core.iam.application.Actor;

@Repository
public class JdbcAccountRepository implements AccountRepository {
    private static final RowMapper<Account> MAPPER = (row, index) -> new Account(
            row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class), row.getString("account_number"),
            row.getString("currency"), AccountStatus.valueOf(row.getString("status")), row.getLong("version"),
            row.getBoolean("ledger_provisioned"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> insert(UUID id, UUID customerId, String currency, Instant now) {
        return jdbc.sql("""
                        INSERT INTO banking.accounts (id, customer_id, currency, created_at, updated_at)
                        VALUES (:id, :customer, :currency, :now, :now)
                        ON CONFLICT (customer_id, currency) DO NOTHING RETURNING *
                        """).param("id", id).param("customer", customerId).param("currency", currency)
                .param("now", Timestamp.from(now)).query(MAPPER).optional();
    }

    @Override
    public List<Account> findByCustomer(UUID customerId) {
        return jdbc.sql("SELECT * FROM banking.accounts WHERE customer_id = :customer ORDER BY account_number")
                .param("customer", customerId).query(MAPPER).list();
    }

    @Override
    public Optional<Account> findOwned(UUID id, UUID customerId, boolean lock) {
        return jdbc.sql("SELECT * FROM banking.accounts WHERE id = :id AND customer_id = :customer" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("customer", customerId).query(MAPPER).optional();
    }

    @Override
    public Optional<Account> findById(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM banking.accounts WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(MAPPER).optional();
    }

    @Override
    public List<Account> lockByIds(java.util.Set<UUID> ids) {
        return jdbc.sql("SELECT * FROM banking.accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids).query(MAPPER).list();
    }

    @Override
    public void markLedgerProvisioned(UUID id) {
        int updated = jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=TRUE WHERE id=:id AND status='ACTIVE'")
                .param("id", id).update();
        if (updated != 1) {
            throw new IllegalStateException("Active locked account required");
        }
    }

    @Override
    public boolean update(Account before, Account after) {
        return jdbc.sql("""
                        UPDATE banking.accounts SET status = :after, version = version + 1, updated_at = :now
                        WHERE id = :id AND version = :version AND status = :before
                        """).param("after", after.status().name()).param("now", Timestamp.from(after.updatedAt()))
                .param("id", before.id()).param("version", before.version()).param("before", before.status().name()).update() == 1;
    }

    @Override
    public void appendHistory(Account before, Account after, AccountReason reason, Actor actor) {
        jdbc.sql("""
                        INSERT INTO banking.account_state_changes
                            (id, account_id, from_status, to_status, reason, actor_issuer, actor_subject, account_version, changed_at)
                        VALUES (:id, :account, :before, :after, :reason, :issuer, :subject, :version, :now)
                        """).param("id", UUID.randomUUID()).param("account", after.id())
                .param("before", before == null ? null : before.status().name(), Types.VARCHAR)
                .param("after", after.status().name()).param("reason", reason.name())
                .param("issuer", actor.issuer()).param("subject", actor.subject())
                .param("version", after.version()).param("now", Timestamp.from(after.updatedAt())).update();
    }
}
