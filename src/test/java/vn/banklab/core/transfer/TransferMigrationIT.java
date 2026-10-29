package vn.banklab.core.transfer;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** SQL-only guards and upgrade evidence, independent of HTTP/service validation. */
@Testcontainers
class TransferMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @Test
    void upgradePreservesCashInAndRejectsPendingImmutableAndMisrepresentedTransfers() throws Exception {
        var source = UUID.randomUUID(); var destination = UUID.randomUUID();
        var sourceLedger = UUID.randomUUID(); var destinationLedger = UUID.randomUUID();
        var cashReceipt = UUID.randomUUID(); var cashJournal = UUID.randomUUID();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).target("5").load().migrate();
        try (var c = connection(); var sql = c.createStatement()) {
            c.setAutoCommit(false);
            account(sql, source, sourceLedger, "sender");
            account(sql, destination, destinationLedger, "recipient");
            journal(sql, cashJournal, cashReceipt, "SIMULATED_CASH_IN", "operator");
            line(sql, cashJournal, UUID.fromString("00000000-0000-0000-0000-000000000001"), "DEBIT");
            line(sql, cashJournal, sourceLedger, "CREDIT");
            finish(sql, cashJournal);
            sql.execute("INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount,journal_id,posted_at) SELECT '" + cashReceipt + "','sql-issuer','operator','preserved','" + "a".repeat(64) + "','" + source + "',100,id,posted_at FROM banking.ledger_journals WHERE id='" + cashJournal + "'");
            c.commit();
        }
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = connection(); var sql = c.createStatement(); var r = sql.executeQuery("SELECT amount,banking.ledger_balance('" + sourceLedger + "'),(SELECT count(*) FROM banking.transfers) FROM banking.cash_ins WHERE id='" + cashReceipt + "'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getBigDecimal(1)).isEqualByComparingTo("100");
            assertThat(r.getBigDecimal(2)).isEqualByComparingTo("100");
            assertThat(r.getLong(3)).isZero();
        }
        try (var c = connection(); var sql = c.createStatement()) {
            assertSql(() -> sql.execute("INSERT INTO banking.transfers (id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount) VALUES ('" + UUID.randomUUID() + "','sql-issuer','sender','pending','" + "a".repeat(64) + "','" + source + "','" + destination + "',1)"));
        }
        for (var mismatch : new String[]{"amount", "reference", "actor", "kind", "timestamp", "direction", "ownership"}) {
            try (var c = connection(); var sql = c.createStatement()) {
                c.setAutoCommit(false);
                var receipt = UUID.randomUUID(); var journal = UUID.randomUUID();
                // Ownership case gives both journal and receipt the same wrong actor.
                var journalActor = mismatch.equals("ownership") ? "not-sender" : mismatch.equals("direction") ? "recipient" : "sender";
                journal(sql, journal, mismatch.equals("reference") ? UUID.randomUUID() : receipt,
                        mismatch.equals("kind") ? "ADJUSTMENT" : "INTERNAL_TRANSFER", journalActor);
                line(sql, journal, sourceLedger, "DEBIT"); line(sql, journal, destinationLedger, "CREDIT"); finish(sql, journal);
                receipt(sql, receipt, journal, mismatch.equals("direction") ? destination : source,
                        mismatch.equals("direction") ? source : destination,
                        mismatch.equals("actor") ? "different-actor" : journalActor,
                        mismatch.equals("amount") ? "101" : "100", mismatch.equals("timestamp"));
                assertSql(c::commit);
                c.rollback();
            }
        }
        // A correct transfer commits and becomes permanently immutable, including its dedupe key.
        var receipt = UUID.randomUUID(); var journal = UUID.randomUUID();
        try (var c = connection(); var sql = c.createStatement()) {
            c.setAutoCommit(false);
            journal(sql, journal, receipt, "INTERNAL_TRANSFER", "sender");
            line(sql, journal, sourceLedger, "DEBIT"); line(sql, journal, destinationLedger, "CREDIT"); finish(sql, journal);
            receipt(sql, receipt, journal, source, destination, "sender", "100", false);
            c.commit();
        }
        try (var c = connection(); var sql = c.createStatement()) {
            for (var change : new String[]{"amount=99", "idempotency_key='changed'", "source_account_id=destination_account_id", "journal_id=NULL,posted_at=NULL"}) {
                assertSql(() -> sql.execute("UPDATE banking.transfers SET " + change + " WHERE id='" + receipt + "'"));
            }
            assertSql(() -> sql.execute("DELETE FROM banking.transfers WHERE id='" + receipt + "'"));
            assertSql(() -> sql.execute("TRUNCATE banking.transfers"));
            try (var r = sql.executeQuery("SELECT banking.ledger_balance('" + sourceLedger + "'),banking.ledger_balance('" + destinationLedger + "'),(SELECT count(*) FROM banking.ledger_journals),(SELECT count(*) FROM banking.transfers)")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo("0");
                assertThat(r.getBigDecimal(2)).isEqualByComparingTo("100");
                assertThat(r.getLong(3)).isEqualTo(2);
                assertThat(r.getLong(4)).isEqualTo(1);
            }
        }
    }

    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static void account(Statement sql, UUID id, UUID ledger, String subject) throws SQLException {
        var customer = UUID.randomUUID();
        sql.execute("INSERT INTO banking.customers (id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES ('" + customer + "','sql-issuer','" + subject + "','Synthetic Transfer Upgrade',now(),now())");
        sql.execute("INSERT INTO banking.accounts (id,customer_id,currency,created_at,updated_at) VALUES ('" + id + "','" + customer + "','VND',now(),now())");
        sql.execute("INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES ('" + ledger + "','TRANSFER_" + subject + "','Synthetic deposit','LIABILITY','CREDIT','VND','" + id + "',now())");
        sql.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='" + id + "'");
    }
    private static void journal(Statement sql, UUID id, UUID reference, String kind, String actor) throws SQLException {
        sql.execute("INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES ('" + id + "','" + reference + "','" + kind + "','VND','DRAFT','sql-issuer','" + actor + "',now())");
    }
    private static void line(Statement sql, UUID journal, UUID ledger, String side) throws SQLException { sql.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES ('" + UUID.randomUUID() + "','" + journal + "','" + ledger + "','VND','" + side + "',100)"); }
    private static void finish(Statement sql, UUID journal) throws SQLException { sql.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='" + journal + "'"); }
    private static void receipt(Statement sql, UUID id, UUID journal, UUID source, UUID destination, String actor, String amount, boolean wrongTime) throws SQLException {
        sql.execute("""
                INSERT INTO banking.transfers (id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount,journal_id,posted_at)
                SELECT '%s','sql-issuer','%s','%s','%s','%s','%s',%s,id,posted_at%s
                FROM banking.ledger_journals WHERE id='%s'
                """.formatted(id, actor, id, "a".repeat(64), source, destination, amount, wrongTime ? "+interval '1 second'" : "", journal));
    }
    private static void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514")); }
}
