package vn.banklab.core.risk;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RiskMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void cutoverWaitsForConcurrentWriterAndInventoriesItsCommittedReceipt()throws Exception{
        try(var c=connection();var s=c.createStatement()){s.execute("CREATE DATABASE risk_cutover");}
        String original=POSTGRES.getJdbcUrl();String url=original.substring(0,original.lastIndexOf('/'))+"/risk_cutover?loggerLevel=OFF";
        Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword()).target("13").load().migrate();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),la=UUID.randomUUID(),lb=UUID.randomUUID(),id=UUID.randomUUID();
        try(var c=connection(url);var s=c.createStatement()){
            c.setAutoCommit(false);account(s,a,la,"sender");account(s,b,lb,"recipient");
            post(s,UUID.randomUUID(),"ADJUSTMENT","operator",UUID.fromString("00000000-0000-0000-0000-000000000001"),"DEBIT",la,"CREDIT");c.commit();
        }
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->{try(var c=connection(url);var s=c.createStatement()){
                c.setAutoCommit(false);transfer(s,id,a,b,la,lb,"sender");ready.countDown();
                if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Cutover writer timed out");c.commit();return null;
            }});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            var migration=pool.submit(()->Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword()).load().migrate());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            try(var c=connection(url);var s=c.createStatement()){
                while(System.nanoTime()<until){
                    try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname='risk_cutover' AND wait_event_type='Lock' AND query LIKE '%SHARE ROW EXCLUSIVE%')")){r.next();blocked=r.getBoolean(1);}
                    if(blocked)break;Thread.sleep(25);
                }
            }
            assertThat(blocked).as("V14 explicitly waits for pre-cutover transfer writers").isTrue();assertThat(migration.isDone()).isFalse();
            release.countDown();writer.get(5,TimeUnit.SECONDS);assertThat(migration.get(8,TimeUnit.SECONDS).migrationsExecuted).isEqualTo(1);
            try(var c=connection(url);var s=c.createStatement();var r=s.executeQuery("SELECT transfer_id,(SELECT count(*) FROM banking.transfer_risk_decisions) FROM banking.transfer_risk_legacy")){
                assertThat(r.next()).isTrue();assertThat(r.getObject(1,UUID.class)).isEqualTo(id);assertThat(r.getLong(2)).isZero();
            }
        }finally{release.countDown();}
    }
    @Test void v14PreservesLegacyMoneyAndEnforcesNewAtomicImmutableRiskEvidence()throws Exception{
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("13").load().migrate();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),la=UUID.randomUUID(),lb=UUID.randomUUID(),legacy=UUID.randomUUID();
        try(var c=connection();var s=c.createStatement()){
            c.setAutoCommit(false);account(s,a,la,"sender");account(s,b,lb,"recipient");
            post(s,UUID.randomUUID(),"ADJUSTMENT","operator",UUID.fromString("00000000-0000-0000-0000-000000000001"),"DEBIT",la,"CREDIT");
            transfer(s,legacy,a,b,la,lb,"sender");c.commit();
        }
        long[] before=counts();
        var latest=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(counts()).containsExactly(before);
        try(var c=connection();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.transfer_risk_legacy),(SELECT count(*) FROM banking.transfer_risk_decisions),banking.ledger_balance('"+la+"'),banking.ledger_balance('"+lb+"')")){
                r.next();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getLong(2)).isZero();assertThat(r.getBigDecimal(3)).isEqualByComparingTo("0");assertThat(r.getBigDecimal(4)).isEqualByComparingTo("100");
            }
            sqlFailure(()->risk(s,legacy,"a".repeat(64)));
            sqlFailure(()->s.execute("INSERT INTO banking.transfer_risk_legacy VALUES('"+UUID.randomUUID()+"')"));
        }
        UUID good=UUID.randomUUID();
        for(String mode:new String[]{"missing","wrong-hash","good"}){
            try(var c=connection();var s=c.createStatement()){
                c.setAutoCommit(false);UUID id=mode.equals("good")?good:UUID.randomUUID();transfer(s,id,b,a,lb,la,"recipient");
                if(mode.equals("missing"))sqlFailure(c::commit);
                else if(mode.equals("wrong-hash"))sqlFailure(()->risk(s,id,"b".repeat(64)));
                else{risk(s,id,"a".repeat(64));c.commit();}
                if(!mode.equals("good"))c.rollback();
            }
        }
        try(var c=connection();var s=c.createStatement()){
            sqlFailure(()->s.execute("UPDATE banking.transfer_risk_decisions SET policy_version='forged' WHERE transfer_id='"+good+"'"));
            sqlFailure(()->s.execute("DELETE FROM banking.transfer_risk_decisions WHERE transfer_id='"+good+"'"));
            sqlFailure(()->s.execute("TRUNCATE banking.transfer_risk_decisions"));sqlFailure(()->s.execute("TRUNCATE banking.transfer_risk_legacy"));
            try(var r=s.executeQuery("SELECT count(*) FROM banking.transfer_risk_decisions")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
    }
    private Connection connection()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private Connection connection(String url)throws SQLException{return DriverManager.getConnection(url,POSTGRES.getUsername(),POSTGRES.getPassword());}
    private long[] counts()throws SQLException{
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM banking.ledger_journals),(SELECT count(*) FROM banking.outbox_events),(SELECT count(*) FROM banking.audit_events),(SELECT count(*) FROM banking.transfers)")){
            r.next();return new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)};
        }
    }
    private void account(Statement s,UUID bank,UUID ledger,String subject)throws SQLException{
        UUID customer=UUID.randomUUID();s.execute("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES('"+customer+"','risk-upgrade','"+subject+"','Synthetic Risk Upgrade',now(),now())");
        s.execute("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES('"+bank+"','"+customer+"','VND',now(),now())");
        s.execute("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES('"+ledger+"','"+ledger+"','Synthetic','LIABILITY','CREDIT','VND','"+bank+"',now())");
        s.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='"+bank+"'");
    }
    private void transfer(Statement s,UUID id,UUID a,UUID b,UUID la,UUID lb,String actor)throws SQLException{
        UUID journal=post(s,id,"INTERNAL_TRANSFER",actor,la,"DEBIT",lb,"CREDIT");
        s.execute("INSERT INTO banking.transfers(id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount,journal_id,posted_at) SELECT '"+id+"','risk-upgrade','"+actor+"','"+id+"','"+"a".repeat(64)+"','"+a+"','"+b+"',100,id,posted_at FROM banking.ledger_journals WHERE id='"+journal+"'");
    }
    private UUID post(Statement s,UUID reference,String kind,String actor,UUID first,String firstSide,UUID second,String secondSide)throws SQLException{
        UUID id=UUID.randomUUID();s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+id+"','"+reference+"','"+kind+"','VND','DRAFT','risk-upgrade','"+actor+"',now())");
        for(var p:new Object[][]{{first,firstSide},{second,secondSide}})s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+id+"','"+p[0]+"','VND','"+p[1]+"',100)");
        s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+id+"'");return id;
    }
    private void risk(Statement s,UUID id,String hash)throws SQLException{s.execute("INSERT INTO banking.transfer_risk_decisions(transfer_id,request_hash,request_id,outcome,policy_version,reason,assessed_at) VALUES('"+id+"','"+hash+"','"+"c".repeat(64)+"','BYPASSED','disabled-v1','RISK_DISABLED',clock_timestamp())");}
    private void sqlFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));}
}
