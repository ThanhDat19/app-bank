package vn.banklab.core.statement;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class StatementMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void upgradePreservesMoneyOutboxAndAuditWithoutCreatingReadArtifacts()throws Exception{
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("12").load().migrate();
        UUID journal=UUID.randomUUID();String payload;
        try(var c=connection();var s=c.createStatement()){
            c.setAutoCommit(false);
            s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+journal+"','"+UUID.randomUUID()+"','ADJUSTMENT','VND','DRAFT','fixture','statement-migration',now())");
            s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+journal+"','00000000-0000-0000-0000-000000000001','VND','DEBIT',1),('"+UUID.randomUUID()+"','"+journal+"','00000000-0000-0000-0000-000000000002','VND','CREDIT',1)");
            s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+journal+"'");c.commit();
            try(var r=s.executeQuery("SELECT payload::text FROM banking.outbox_events WHERE aggregate_id='"+journal+"'")){assertThat(r.next()).isTrue();payload=r.getString(1);}
        }
        var flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.account_history_snapshots),(SELECT count(*) FROM banking.account_history_entries),(SELECT count(*) FROM banking.audit_events),(SELECT payload::text FROM banking.outbox_events),banking.ledger_balance('00000000-0000-0000-0000-000000000001')")){
            assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getLong(2)).isZero();assertThat(r.getLong(3)).isEqualTo(1);assertThat(r.getString(4)).isEqualTo(payload);assertThat(r.getBigDecimal(5)).isEqualByComparingTo("1");
        }
    }
    private Connection connection()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
}
