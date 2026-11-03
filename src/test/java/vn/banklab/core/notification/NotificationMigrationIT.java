package vn.banklab.core.notification;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class NotificationMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void upgradePreservesMoneyOutboxPayloadAndLeaseWithoutBackfillingNotifications() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("10").load().migrate();
        UUID journal=UUID.randomUUID();String payload;
        try(var c=connection();var s=c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+journal+"','"+UUID.randomUUID()+"','ADJUSTMENT','VND','DRAFT','sql-fixture','operator',now())");
            s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+journal+"','00000000-0000-0000-0000-000000000001','VND','DEBIT',1),('"+UUID.randomUUID()+"','"+journal+"','00000000-0000-0000-0000-000000000002','VND','CREDIT',1)");
            s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+journal+"'");c.commit();
            s.execute("UPDATE banking.outbox_deliveries SET state='LEASED',attempts=1,claim_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '5 minutes'");c.commit();
            try(var r=s.executeQuery("SELECT payload::text FROM banking.outbox_events")) {assertThat(r.next()).isTrue();payload=r.getString(1);}
        }
        var flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT e.payload::text,d.state,d.attempts,(SELECT count(*) FROM banking.notifications),(SELECT count(*) FROM banking.notification_inbox),banking.ledger_balance('00000000-0000-0000-0000-000000000001') FROM banking.outbox_events e JOIN banking.outbox_deliveries d USING(event_id)")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(payload);assertThat(r.getString(2)).isEqualTo("LEASED");assertThat(r.getInt(3)).isEqualTo(1);
            assertThat(r.getLong(4)).isZero();assertThat(r.getLong(5)).isZero();assertThat(r.getBigDecimal(6)).isEqualByComparingTo("1");assertThat(r.next()).isFalse();
        }
    }
    private Connection connection() throws SQLException {return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
}
