package vn.banklab.core.cashin;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Direct SQL evidence, including a V4 database with pre-existing posted money. */
@Testcontainers
class CashInMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @Test
    void upgradePreservesPostedMoneyAndRejectsReceiptsThatMisrepresentTheirJournal() throws Exception {
        var customer = UUID.randomUUID();
        var bank = UUID.randomUUID();
        var ledger = UUID.randomUUID();
        var journal = UUID.randomUUID();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).target("4").load().migrate();
        try (var connection = connection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute("INSERT INTO banking.customers (id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES ('" + customer + "','sql-issuer','preserved','Synthetic Upgrade',now(),now())");
            sql.execute("INSERT INTO banking.accounts (id,customer_id,currency,created_at,updated_at) VALUES ('" + bank + "','" + customer + "','VND',now(),now())");
            sql.execute("INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES ('" + ledger + "','UPGRADE_CUSTOMER','Synthetic deposit','LIABILITY','CREDIT','VND','" + bank + "',now())");
            sql.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='" + bank + "'");
            post(sql, journal, UUID.randomUUID(), ledger, "ADJUSTMENT");
            connection.commit();
        }
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = connection(); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT banking.ledger_balance('" + ledger + "')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo("100");
            }
            try (var result = sql.executeQuery("SELECT count(*) FROM banking.cash_ins")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
        // Even a fully balanced and valid ledger journal cannot back a misleading receipt.
        for (var mismatch : new String[]{"amount", "reference", "actor", "kind", "timestamp"}) {
            try (var connection = connection(); var sql = connection.createStatement()) {
                connection.setAutoCommit(false);
                var receipt = UUID.randomUUID();
                var nextJournal = UUID.randomUUID();
                post(sql, nextJournal, mismatch.equals("reference") ? UUID.randomUUID() : receipt, ledger,
                        mismatch.equals("kind") ? "ADJUSTMENT" : "SIMULATED_CASH_IN");
                sql.execute("""
                        INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount,journal_id,posted_at)
                        SELECT '%s','sql-issuer','%s','%s','%s','%s',%s,id,posted_at%s
                        FROM banking.ledger_journals WHERE id='%s'
                        """.formatted(receipt, mismatch.equals("actor") ? "different-actor" : "sql-actor", receipt,
                        "a".repeat(64), bank, mismatch.equals("amount") ? "101" : "100",
                        mismatch.equals("timestamp") ? "+interval '1 second'" : "", nextJournal));
                assertThatThrownBy(connection::commit).isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).as(mismatch).isEqualTo("23514"));
                connection.rollback();
            }
        }
        try (var connection = connection(); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT count(*), (SELECT banking.ledger_balance('" + ledger + "')) FROM banking.ledger_journals")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1);
            assertThat(result.getBigDecimal(2)).isEqualByComparingTo("100");
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void post(Statement sql, UUID journal, UUID reference, UUID ledger, String kind) throws SQLException {
        sql.execute("INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES ('" + journal + "','" + reference + "','" + kind + "','VND','DRAFT','sql-issuer','sql-actor',now())");
        sql.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES ('" + UUID.randomUUID() + "','" + journal + "','00000000-0000-0000-0000-000000000001','VND','DEBIT',100),('" + UUID.randomUUID() + "','" + journal + "','" + ledger + "','VND','CREDIT',100)");
        sql.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='" + journal + "'");
    }
}
