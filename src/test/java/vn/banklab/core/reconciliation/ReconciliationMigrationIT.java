package vn.banklab.core.reconciliation;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ReconciliationMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void legacyCutoverPreservesMoneyAndEventsWithoutInventingHistoricalAudit() throws Exception {
        migrate("9");UUID legacy=UUID.randomUUID(),covered=UUID.randomUUID(),fresh=UUID.randomUUID();post(legacy);
        migrate("11");post(covered);
        try(var c=connection();var s=c.createStatement()) {
            s.execute("INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,error_code) VALUES('legacy-unknown',0,1,'"+"a".repeat(64)+"',1,now()-interval '2 days','PROCESSING_FAILED')");
        }
        var latest=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.reconciliation_journal_baseline),(SELECT count(*) FROM banking.reconciliation_journal_baseline WHERE legacy_eventless),(SELECT count(*) FROM banking.outbox_events),(SELECT count(*) FROM banking.audit_events),banking.ledger_balance('00000000-0000-0000-0000-000000000001')")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getLong(3)).isEqualTo(1);assertThat(r.getLong(4)).isZero();assertThat(r.getBigDecimal(5)).isEqualByComparingTo("2");
            }
            try(var r=s.executeQuery("SELECT count(*) FILTER(WHERE code<>'LEGACY_RETRY_STATUS_UNKNOWN'),count(*) FILTER(WHERE code='LEGACY_RETRY_STATUS_UNKNOWN') FROM banking.reconciliation_scan(clock_timestamp(),300)")){
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getLong(2)).isEqualTo(1);
            }
        }
        post(fresh);
        try(var c=connection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.outbox_events),(SELECT count(*) FROM banking.audit_events),(SELECT count(*) FROM banking.reconciliation_journal_baseline),banking.ledger_balance('00000000-0000-0000-0000-000000000001')")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getLong(3)).isEqualTo(2);assertThat(r.getBigDecimal(4)).isEqualByComparingTo("3");
            }
            assertThatThrownBy(()->s.execute("INSERT INTO banking.reconciliation_journal_baseline VALUES('"+fresh+"',true)"))
                    .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(()->s.execute("UPDATE banking.reconciliation_journal_baseline SET legacy_eventless=true WHERE journal_id='"+covered+"'"))
                    .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
            try(var r=s.executeQuery("SELECT count(*) FILTER(WHERE code<>'LEGACY_RETRY_STATUS_UNKNOWN'),count(*) FILTER(WHERE code='LEGACY_RETRY_STATUS_UNKNOWN') FROM banking.reconciliation_scan(clock_timestamp(),300)")){
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getLong(2)).isEqualTo(1);
            }
        }
    }
    private void migrate(String target){Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target(target).load().migrate();}
    private Connection connection() throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private void post(UUID id) throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+id+"','"+UUID.randomUUID()+"','ADJUSTMENT','VND','DRAFT','sql-fixture','operator',now())");
            s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+id+"','00000000-0000-0000-0000-000000000001','VND','DEBIT',1),('"+UUID.randomUUID()+"','"+id+"','00000000-0000-0000-0000-000000000002','VND','CREDIT',1)");
            s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+id+"'");c.commit();
        }
    }
}
