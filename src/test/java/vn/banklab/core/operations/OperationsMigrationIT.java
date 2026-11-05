package vn.banklab.core.operations;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OperationsMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Test void v16PreservesLegacyAccountReversalAndUnknownReservationSnapshots()throws Exception{
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("15").load().migrate();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),la=UUID.randomUUID(),lb=UUID.randomUUID(),receipt=UUID.randomUUID(),hold=UUID.randomUUID();
        try(var c=connection();var s=c.createStatement()){
            c.setAutoCommit(false);account(s,a,la,"sender");account(s,b,lb,"recipient");
            post(s,UUID.randomUUID(),"ADJUSTMENT","operator",UUID.fromString("00000000-0000-0000-0000-000000000001"),la,100);
            UUID journal=post(s,receipt,"INTERNAL_TRANSFER","sender",la,lb,10);
            s.execute("INSERT INTO banking.transfers(id,actor_issuer,actor_subject,idempotency_key,request_hash,source_account_id,destination_account_id,amount,journal_id,posted_at) SELECT '"+receipt+"','external-upgrade','sender','preserved','"+"a".repeat(64)+"','"+a+"','"+b+"',10,id,posted_at FROM banking.ledger_journals WHERE id='"+journal+"'");
            s.execute("INSERT INTO banking.transfer_risk_decisions(transfer_id,request_hash,request_id,outcome,policy_version,reason,assessed_at) VALUES('"+receipt+"','"+"a".repeat(64)+"','"+"b".repeat(64)+"','BYPASSED','disabled-v1','RISK_DISABLED',clock_timestamp())");
            s.execute("INSERT INTO banking.holds(id,actor_issuer,actor_subject,idempotency_key,account_id,destination_account_id,amount,expires_at,state) VALUES('"+hold+"','external-upgrade','sender','preserved-hold','"+a+"','"+b+"',20,now()+interval '1 day','PENDING')");
            s.execute("UPDATE banking.holds SET state='ACTIVE' WHERE id='"+hold+"'");
            legacyAndExternal(s,a,b,la,lb,journal); c.commit();
        }
        String before=inventory();
        var latest=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);assertThat(latest.migrate().migrationsExecuted).isZero();assertThat(inventory()).isEqualTo(before);
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT banking.ledger_balance('"+la+"'),banking.reserved_balance('"+a+"'),banking.reserved_balance_at('"+a+"',statement_timestamp()),banking.reservation_count_at('"+a+"',statement_timestamp()),(SELECT count(*) FROM banking.external_payments),(SELECT count(*) FROM banking.external_payment_history)")){
            r.next();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("100");assertThat(r.getBigDecimal(2)).isEqualByComparingTo("30");assertThat(r.getBigDecimal(3)).isEqualByComparingTo("30");assertThat(r.getLong(4)).isEqualTo(2);assertThat(r.getLong(5)).isEqualTo(1);assertThat(r.getLong(6)).isEqualTo(2);
        }
    }
    private void legacyAndExternal(Statement s,UUID a,UUID b,UUID la,UUID lb,UUID original)throws SQLException {
        UUID reversal=UUID.randomUUID();UUID inverse=post(s,reversal,"REVERSAL","legacy-checker",lb,la,10);
        s.execute("INSERT INTO banking.reversals(id,actor_issuer,actor_subject,idempotency_key,request_hash,original_journal_id,reason,journal_id,original_kind,amount,posted_at) SELECT '"+reversal+"','external-upgrade','legacy-checker','old-reversal','"+"c".repeat(64)+"','"+original+"','OPERATOR_ERROR',id,'INTERNAL_TRANSFER',10,posted_at FROM banking.ledger_journals WHERE id='"+inverse+"'");
        s.execute("UPDATE banking.accounts SET status='FROZEN',version=1,updated_at=clock_timestamp() WHERE id='"+b+"'");
        s.execute("INSERT INTO banking.account_state_changes(id,account_id,from_status,to_status,reason,actor_issuer,actor_subject,account_version,changed_at) SELECT '"+UUID.randomUUID()+"',id,'ACTIVE','FROZEN','SECURITY_REVIEW','external-upgrade','legacy-operator',1,updated_at FROM banking.accounts WHERE id='"+b+"'");
        // Restore before preparing external obligations, retaining real pre-V16 lifecycle history.
        s.execute("UPDATE banking.accounts SET status='ACTIVE',version=2,updated_at=clock_timestamp() WHERE id='"+b+"'");
        s.execute("INSERT INTO banking.account_state_changes(id,account_id,from_status,to_status,reason,actor_issuer,actor_subject,account_version,changed_at) SELECT '"+UUID.randomUUID()+"',id,'FROZEN','ACTIVE','REVIEW_CLEARED','external-upgrade','legacy-operator',2,updated_at FROM banking.accounts WHERE id='"+b+"'");
        UUID external=UUID.randomUUID();
        s.execute("INSERT INTO banking.external_payments(id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,settlement_account_id,beneficiary,amount,reserved_amount,partner_instance,risk_outcome,risk_policy,risk_reason,risk_assessed_at,state) VALUES('"+external+"','external-upgrade','sender','unknown-preserved','"+"d".repeat(64)+"','"+a+"','"+b+"','SIM_UPGRADE',10,10,'"+UUID.randomUUID()+"','BYPASSED','disabled-v1','RISK_DISABLED',clock_timestamp(),'CREATING')");
        s.execute("UPDATE banking.external_payments SET state='PREPARED',version=1,updated_at=clock_timestamp() WHERE id='"+external+"'");
        s.execute("UPDATE banking.external_payments SET state='UNKNOWN',version=2,updated_at=clock_timestamp() WHERE id='"+external+"'");
    }
    private Connection connection()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private String inventory()throws SQLException{
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT jsonb_build_object('journals',(SELECT jsonb_agg(to_jsonb(j) ORDER BY id) FROM banking.ledger_journals j),'postings',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM banking.ledger_postings p),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY event_id) FROM banking.outbox_events e),'audit',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM banking.audit_events a),'risk',(SELECT jsonb_agg(to_jsonb(d) ORDER BY transfer_id) FROM banking.transfer_risk_decisions d),'holds',(SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM banking.holds h),'accounts',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM banking.accounts a),'history',(SELECT jsonb_agg(to_jsonb(h)-'operation_request_id' ORDER BY id) FROM banking.account_state_changes h),'reversals',(SELECT jsonb_agg(to_jsonb(v)-'operation_request_id' ORDER BY id) FROM banking.reversals v),'external',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM banking.external_payments e),'externalHistory',(SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM banking.external_payment_history h))::text")){r.next();return r.getString(1);}
    }
    private void account(Statement s,UUID bank,UUID ledger,String subject)throws SQLException{
        UUID customer=UUID.randomUUID();s.execute("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES('"+customer+"','external-upgrade','"+subject+"','Synthetic Upgrade',now(),now())");
        s.execute("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES('"+bank+"','"+customer+"','VND',now(),now())");
        s.execute("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES('"+ledger+"','"+ledger+"','Synthetic Upgrade','LIABILITY','CREDIT','VND','"+bank+"',now())");s.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='"+bank+"'");
    }
    private UUID post(Statement s,UUID ref,String kind,String actor,UUID debit,UUID credit,int amount)throws SQLException{
        UUID id=UUID.randomUUID();s.execute("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES('"+id+"','"+ref+"','"+kind+"','VND','DRAFT','external-upgrade','"+actor+"',now())");
        for(var line:new Object[][]{{debit,"DEBIT"},{credit,"CREDIT"}})s.execute("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES('"+UUID.randomUUID()+"','"+id+"','"+line[0]+"','VND','"+line[1]+"',"+amount+")");
        s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='"+id+"'");return id;
    }
}
