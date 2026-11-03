package vn.banklab.core.outbox;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void upgradePreservesLegacyJournalWithoutBackfillAndRejectsForgedPayload() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("9").load().migrate();
        UUID old=UUID.randomUUID();
        try(var c=connection()) { post(c,old); }
        var flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4); assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.outbox_events),(SELECT count(*) FROM banking.outbox_deliveries),banking.ledger_balance('00000000-0000-0000-0000-000000000001')")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isZero(); assertThat(r.getLong(2)).isZero(); assertThat(r.getBigDecimal(3)).isEqualByComparingTo("1");
            }
            assertThatThrownBy(()->s.execute("INSERT INTO banking.outbox_events(aggregate_id,aggregate_type,aggregate_version,event_type,schema_version,occurred_at,payload) SELECT id,'LedgerJournal',1,'LedgerJournalPosted',1,posted_at,'{}'::jsonb FROM banking.ledger_journals WHERE id='"+old+"'"))
                    .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
            post(c,UUID.randomUUID());
            try(var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.outbox_events),(SELECT count(*) FROM banking.outbox_deliveries),banking.ledger_balance('00000000-0000-0000-0000-000000000001')")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(1); assertThat(r.getLong(2)).isEqualTo(1); assertThat(r.getBigDecimal(3)).isEqualByComparingTo("2");
            }
        }
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private void post(Connection c,UUID id) throws SQLException {
        c.setAutoCommit(false);
        try(var s=c.createStatement()) {
            s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+id+"','"+UUID.randomUUID()+"','ADJUSTMENT','VND','DRAFT','sql-fixture','operator',now())");
            s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+id+"','00000000-0000-0000-0000-000000000001','VND','DEBIT',1),('"+UUID.randomUUID()+"','"+id+"','00000000-0000-0000-0000-000000000002','VND','CREDIT',1)");
            s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+id+"'"); c.commit();
        } finally { c.setAutoCommit(true); }
    }
}
