package vn.banklab.core.reversal;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Independent SQL evidence: upgrade real posted receipts, then bypass all Java validations. */
@Testcontainers
class ReversalMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @Test
    void upgradePreservesOriginalsAndDatabaseEnforcesFullAtomicOnceOnlyInverse() throws Exception {
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var la = UUID.randomUUID(); var lb = UUID.randomUUID();
        var cash = UUID.randomUUID(); var original = UUID.randomUUID(); var transferReceipt = UUID.randomUUID();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).target("6").load().migrate();
        try (var c = connection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            account(s, a, la, "sender"); account(s, b, lb, "recipient");
            cashIn(s, cash, a, la, "100");
            post(s, original, transferReceipt, "INTERNAL_TRANSFER", "sender", la, lb, "40");
            s.execute("""
                    INSERT INTO banking.transfers (id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount,journal_id,posted_at)
                    SELECT '%s','sql-issuer','sender','preserved-transfer','%s','%s','%s',40,id,posted_at FROM banking.ledger_journals WHERE id='%s'
                    """.formatted(transferReceipt, "a".repeat(64), a, b, original));
            c.commit();
        }
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT banking.ledger_balance('" + la + "'),banking.ledger_balance('" + lb + "'),(SELECT count(*) FROM banking.reversals),(SELECT amount FROM banking.transfers WHERE id='" + transferReceipt + "')")) {
            assertThat(r.next()).isTrue(); assertThat(r.getBigDecimal(1)).isEqualByComparingTo("60");
            assertThat(r.getBigDecimal(2)).isEqualByComparingTo("40"); assertThat(r.getLong(3)).isZero(); assertThat(r.getBigDecimal(4)).isEqualByComparingTo("40");
        }
        try (var c = connection(); var s = c.createStatement()) {
            assertSql(() -> s.execute("INSERT INTO banking.reversals (id,actor_issuer,actor_subject,idempotency_key,request_hash,original_journal_id,reason) VALUES ('" + UUID.randomUUID() + "','sql-issuer','operator','pending','" + "a".repeat(64) + "','" + original + "','OPERATOR_ERROR')"), "23514");
        }
        for (var mismatch : new String[]{"amount", "partial", "direction", "reference", "actor", "kind", "timestamp", "original-kind", "eligibility", "orphan"}) {
            try (var c = connection(); var s = c.createStatement()) {
                c.setAutoCommit(false);
                var receipt = UUID.randomUUID(); var inverse = UUID.randomUUID(); var chosenOriginal = original;
                var kind = "INTERNAL_TRANSFER"; var amount = "40";
                if (mismatch.equals("eligibility")) {
                    chosenOriginal = UUID.randomUUID(); kind = "SIMULATED_CASH_IN"; amount = "10";
                    post(s, chosenOriginal, UUID.randomUUID(), kind, "operator", clearing(), la, amount);
                    post(s, inverse, receipt, "REVERSAL", "operator", la, clearing(), amount);
                } else {
                    post(s, inverse, mismatch.equals("reference") ? UUID.randomUUID() : receipt,
                            mismatch.equals("kind") ? "ADJUSTMENT" : "REVERSAL", "operator",
                            mismatch.equals("direction") ? la : lb, mismatch.equals("direction") ? lb : la,
                            mismatch.equals("partial") ? "20" : "40");
                }
                if (!mismatch.equals("orphan")) {
                    receipt(s, receipt, chosenOriginal, inverse, mismatch.equals("original-kind") ? "SIMULATED_CASH_IN" : kind,
                            mismatch.equals("actor") ? "other-operator" : "operator",
                            mismatch.equals("amount") ? "39" : amount, mismatch.equals("timestamp"));
                }
                assertSql(c::commit, "23514"); c.rollback();
            }
        }
        // Correct receipt commits. The original journal and public transfer receipt remain POSTED.
        var completed = UUID.randomUUID(); var inverse = UUID.randomUUID();
        try (var c = connection(); var s = c.createStatement()) {
            c.setAutoCommit(false); post(s, inverse, completed, "REVERSAL", "operator", lb, la, "40");
            receipt(s, completed, original, inverse, "INTERNAL_TRANSFER", "operator", "40", false); c.commit();
        }
        try (var c = connection(); var s = c.createStatement()) {
            for (var change : new String[]{"reason='DUPLICATE_PROCESSING'", "idempotency_key='changed'", "amount=20", "journal_id=NULL,posted_at=NULL,amount=NULL,original_kind=NULL"}) {
                assertSql(() -> s.execute("UPDATE banking.reversals SET " + change + " WHERE id='" + completed + "'"), "23514");
            }
            assertSql(() -> s.execute("DELETE FROM banking.reversals WHERE id='" + completed + "'"), "23514");
            assertSql(() -> s.execute("TRUNCATE banking.reversals"), "23514");
        }
        // Re-fund the recipient so a second inverse is financially possible; uniqueness must reject it,
        // independently of insufficient-funds or the Java original-journal lock.
        try (var c = connection(); var s = c.createStatement()) { c.setAutoCommit(false); cashIn(s, UUID.randomUUID(), b, lb, "40"); c.commit(); }
        try (var c = connection(); var s = c.createStatement()) {
            c.setAutoCommit(false); var duplicate = UUID.randomUUID(); var secondJournal = UUID.randomUUID();
            post(s, secondJournal, duplicate, "REVERSAL", "another-operator", lb, la, "40");
            assertSql(() -> receipt(s, duplicate, original, secondJournal, "INTERNAL_TRANSFER", "another-operator", "40", false), "23505"); c.rollback();
        }
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT banking.ledger_balance('" + la + "'),banking.ledger_balance('" + lb + "'),(SELECT count(*) FROM banking.ledger_journals),(SELECT count(*) FROM banking.reversals),(SELECT status FROM banking.ledger_journals WHERE id='" + original + "')")) {
            assertThat(r.next()).isTrue(); assertThat(r.getBigDecimal(1)).isEqualByComparingTo("100"); assertThat(r.getBigDecimal(2)).isEqualByComparingTo("40");
            assertThat(r.getLong(3)).isEqualTo(4); assertThat(r.getLong(4)).isEqualTo(1); assertThat(r.getString(5)).isEqualTo("POSTED");
        }
    }

    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static UUID clearing() { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
    private static void account(Statement s, UUID id, UUID ledger, String subject) throws SQLException {
        var customer = UUID.randomUUID();
        s.execute("INSERT INTO banking.customers (id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES ('" + customer + "','sql-issuer','" + subject + "','Synthetic Reversal Upgrade',now(),now())");
        s.execute("INSERT INTO banking.accounts (id,customer_id,currency,created_at,updated_at) VALUES ('" + id + "','" + customer + "','VND',now(),now())");
        s.execute("INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES ('" + ledger + "','REVERSAL_" + subject + "','Synthetic deposit','LIABILITY','CREDIT','VND','" + id + "',now())");
        s.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='" + id + "'");
    }
    private static void post(Statement s, UUID id, UUID reference, String kind, String actor, UUID debit, UUID credit, String amount) throws SQLException {
        s.execute("INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES ('" + id + "','" + reference + "','" + kind + "','VND','DRAFT','sql-issuer','" + actor + "',now())");
        s.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES ('" + UUID.randomUUID() + "','" + id + "','" + debit + "','VND','DEBIT'," + amount + "),('" + UUID.randomUUID() + "','" + id + "','" + credit + "','VND','CREDIT'," + amount + ")");
        s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='" + id + "'");
    }
    private static void cashIn(Statement s, UUID journal, UUID bank, UUID ledger, String amount) throws SQLException {
        var id = UUID.randomUUID(); post(s, journal, id, "SIMULATED_CASH_IN", "operator", clearing(), ledger, amount);
        s.execute("INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount,journal_id,posted_at) SELECT '" + id + "','sql-issuer','operator','" + id + "','" + "a".repeat(64) + "','" + bank + "'," + amount + ",id,posted_at FROM banking.ledger_journals WHERE id='" + journal + "'");
    }
    private static void receipt(Statement s, UUID id, UUID original, UUID inverse, String kind, String actor, String amount, boolean badTime) throws SQLException {
        s.execute("""
                INSERT INTO banking.reversals (id,actor_issuer,actor_subject,idempotency_key,request_hash,original_journal_id,reason,journal_id,original_kind,amount,posted_at)
                SELECT '%s','sql-issuer','%s','%s','%s','%s','OPERATOR_ERROR',id,'%s',%s,posted_at%s
                FROM banking.ledger_journals WHERE id='%s'
                """.formatted(id, actor, id, "a".repeat(64), original, kind, amount, badTime ? "+interval '1 second'" : "", inverse));
    }
    private static void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) { assertThatThrownBy(action).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(state)); }
}
