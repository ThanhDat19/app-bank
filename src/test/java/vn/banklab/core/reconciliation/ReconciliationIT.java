package vn.banklab.core.reconciliation;

import java.util.*;
import java.sql.*;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.support.SignedTokenServer;
import vn.banklab.core.reconciliation.application.*;
import vn.banklab.core.outbox.application.*;
import vn.banklab.core.notification.application.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationIT {
    static final SignedTokenServer TOKENS;
    static {try {TOKENS=new SignedTokenServer();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri",TOKENS::issuer);r.add("bank.iam.jwk-set-uri",TOKENS::jwks);r.add("bank.iam.audience",()->"bank-api");
        r.add("bank.reconciliation.enabled",()->false);r.add("bank.kafka.enabled",()->false);r.add("bank.outbox.relay.enabled",()->false);
    }
    @AfterAll static void closeTokens(){TOKENS.close();}
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ReconciliationService service;
    @Autowired OutboxRepository outbox;
    @Autowired NotificationService notifications;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newHttpClient();
    private final JsonMapper json=JsonMapper.builder().build();
    private static final String ROOT="/api/v1/operations/reconciliation/runs";
    private static final UUID CLEARING=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private record Owner(UUID bank,UUID ledger,String token){}

    @BeforeEach void settlePreviousFixtures() {
        List<OutboxRepository.Claim> claims;
        do {claims=outbox.claim(100,30000);claims.forEach(c->outbox.acknowledge(c.event().eventId(),c.token()));}while(!claims.isEmpty());
        var ids=jdbc.sql("SELECT event_id FROM banking.outbox_events WHERE event_id NOT IN(SELECT event_id FROM banking.notification_inbox)").query(UUID.class).list();
        for(UUID id:ids) accept(event(id));
        jdbc.sql("DELETE FROM banking.notification_failures").update();
    }
    @Test void healthySnapshotHasNoFindingsAndOperatorAuditIsVerified() throws Exception {
        var owner=owner();var e=post(owner,false);accept(e);
        var claim=outbox.claim(100,30000);claim.forEach(c->outbox.acknowledge(c.event().eventId(),c.token()));
        var response=call("POST",ROOT,operator(),null);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var report=json.readTree(response.body());assertThat(report.path("run").path("status").asString()).isEqualTo("CLEAN");
        UUID id=UUID.fromString(report.path("run").path("id").asString());
        assertThat(report.path("summary").size()).isZero();assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(call("GET",ROOT+"/"+id,operator(),null).body())).isEqualTo(report);
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.audit_events WHERE action='RECONCILIATION_COMPLETED' AND resource_id=:id").param("id",id).query(String.class).single()).isEqualTo("recon-operator");
        assertThat(report.path("run").path("actorType").asString()).isEqualTo("OPERATOR");
    }
    @Test void freshBacklogIsCleanButAgedDeliveryIsWarningAndReportStaysImmutable() throws Exception {
        var owner=owner();post(owner,false);assertThat(service.runScheduled().run().status()).isEqualTo("CLEAN");
        var old=post(owner,true);var warning=service.runScheduled();assertThat(warning.run().status()).isEqualTo("WARNING");
        assertThat(codes(warning)).contains("OUTBOX_DELIVERY_LAG");assertThat(warning.run().errorCount()).isZero();
        assertThat(warning.run().actorType()).isEqualTo("SYSTEM");
        settlePreviousFixtures();assertThat(service.runScheduled().run().status()).isEqualTo("CLEAN");
        assertThat(jdbc.sql("SELECT status FROM banking.reconciliation_runs WHERE id=:id").param("id",warning.run().id()).query(String.class).single()).isEqualTo("WARNING");
    }
    @Test void deliveredWithoutInboxIsLagNotProjectionCorruption() throws Exception {
        var e=post(owner(),false);var c=outbox.claim(1,30000).getFirst();outbox.acknowledge(c.event().eventId(),c.token());
        mutate("outbox_deliveries",()->jdbc.sql("UPDATE banking.outbox_deliveries SET delivered_at=clock_timestamp()-interval '2 days' WHERE event_id=:id").param("id",e.eventId()).update());
        var r=service.runScheduled();assertThat(codes(r)).contains("NOTIFICATION_PROCESSING_LAG").doesNotContain("NOTIFICATION_MISSING");
        assertThat(r.run().errorCount()).isZero();accept(e);
    }
    @Test void ledgerCorruptionIsDetectedWithoutRepair() throws Exception {
        var o=owner();var e=post(o,false);
        mutate("ledger_postings",()->jdbc.sql("UPDATE banking.ledger_postings SET side='DEBIT' WHERE journal_id=:id AND ledger_account_id=:ledger").param("id",e.aggregateId()).param("ledger",o.ledger()).update());
        try {
            var r=service.runScheduled();assertThat(r.run().status()).isEqualTo("MISMATCH");
            assertThat(codes(r)).contains("JOURNAL_INVALID","CUSTOMER_BALANCE_INVALID","OUTBOX_CONTENT_MISMATCH");
            assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",o.ledger()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("-1");
        } finally {mutate("ledger_postings",()->jdbc.sql("UPDATE banking.ledger_postings SET side='CREDIT' WHERE journal_id=:id AND ledger_account_id=:ledger").param("id",e.aggregateId()).param("ledger",o.ledger()).update());}
    }
    @Test void missingOutboxDeliveryAndProjectionAreDetected() throws Exception {
        var o=owner();var e=post(o,false);
        mutate("outbox_deliveries",()->jdbc.sql("DELETE FROM banking.outbox_deliveries WHERE event_id=:id").param("id",e.eventId()).update());
        try {assertThat(codes(service.runScheduled())).contains("OUTBOX_DELIVERY_MISSING");}
        finally {restoreDelivery(e);}
        accept(e);
        mutate("notifications",()->jdbc.sql("DELETE FROM banking.notifications WHERE event_id=:id").param("id",e.eventId()).update());
        try {assertThat(codes(service.runScheduled())).contains("NOTIFICATION_MISSING");}
        finally {jdbc.sql("INSERT INTO banking.notifications(event_id,account_id,journal_id,kind,occurred_at) SELECT :event,:account,j.id,j.kind,j.posted_at FROM banking.ledger_journals j WHERE j.id=:journal")
                    .param("event",e.eventId()).param("account",o.bank()).param("journal",e.aggregateId()).update();}
    }
    @Test void corruptInboxHashAndNotificationContentAreDetected() throws Exception {
        var e=post(owner(),false);accept(e);
        String original=jdbc.sql("SELECT payload_hash FROM banking.notification_inbox WHERE event_id=:id").param("id",e.eventId()).query(String.class).single();
        mutate("notification_inbox",()->jdbc.sql("UPDATE banking.notification_inbox SET payload_hash=:hash WHERE event_id=:id").param("hash","0".repeat(64)).param("id",e.eventId()).update());
        mutate("notifications",()->jdbc.sql("UPDATE banking.notifications SET kind='REVERSAL' WHERE event_id=:id").param("id",e.eventId()).update());
        try {assertThat(codes(service.runScheduled())).contains("INBOX_HASH_MISMATCH","NOTIFICATION_CONTENT_MISMATCH");}
        finally {
            mutate("notification_inbox",()->jdbc.sql("UPDATE banking.notification_inbox SET payload_hash=:hash WHERE event_id=:id").param("hash",original).param("id",e.eventId()).update());
            mutate("notifications",()->jdbc.sql("UPDATE banking.notifications SET kind='ADJUSTMENT' WHERE event_id=:id").param("id",e.eventId()).update());
        }
    }
    @Test void quarantinesAndStaleRetriesAreWarningsWithoutFinancialMutation() {
        jdbc.sql("INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,quarantined,error_code) VALUES('fixture',0,1,:hash,3,clock_timestamp(),true,'INVALID_EVENT'),('fixture',0,2,:hash,1,clock_timestamp()-interval '2 days',false,'PROCESSING_FAILED')").param("hash","a".repeat(64)).update();
        var r=service.runScheduled();assertThat(codes(r)).contains("NOTIFICATION_QUARANTINED","NOTIFICATION_RETRY_LAG");assertThat(r.run().errorCount()).isZero();
    }
    @Test void missingAndForgedJournalAuditAreDetectedAndAuditIsAppendOnly() throws Exception {
        var e=post(owner(),false);
        mutate("audit_events",()->jdbc.sql("DELETE FROM banking.audit_events WHERE action='LEDGER_JOURNAL_POSTED' AND resource_id=:id").param("id",e.aggregateId()).update());
        try {assertThat(codes(service.runScheduled())).contains("JOURNAL_AUDIT_MISSING");}
        finally {jdbc.sql("INSERT INTO banking.audit_events(actor_issuer,actor_subject,actor_type,action,resource_type,resource_id,occurred_at) SELECT actor_issuer,actor_subject,'JOURNAL_ACTOR','LEDGER_JOURNAL_POSTED','LEDGER_JOURNAL',id,posted_at FROM banking.ledger_journals WHERE id=:id").param("id",e.aggregateId()).update();}
        mutate("audit_events",()->jdbc.sql("UPDATE banking.audit_events SET actor_subject='forged' WHERE resource_id=:id").param("id",e.aggregateId()).update());
        try {assertThat(codes(service.runScheduled())).contains("AUDIT_SOURCE_MISMATCH");}
        finally {mutate("audit_events",()->jdbc.sql("UPDATE banking.audit_events SET actor_subject='fixture-operator' WHERE resource_id=:id").param("id",e.aggregateId()).update());}
        assertThatThrownBy(()->jdbc.sql("DELETE FROM banking.audit_events WHERE resource_id=:id").param("id",e.aggregateId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void auditInsertFaultRollsBackFinancialJournalEventAndMoney() throws Exception {
        var o=owner();long journals=number("ledger_journals"),events=number("outbox_events");
        jdbc.sql("ALTER TABLE banking.audit_events ADD CONSTRAINT test_audit_fault CHECK(action<>'LEDGER_JOURNAL_POSTED') NOT VALID").update();
        try {assertThatThrownBy(()->post(o,false)).isInstanceOf(Exception.class);}
        finally {jdbc.sql("ALTER TABLE banking.audit_events DROP CONSTRAINT test_audit_fault").update();}
        assertThat(number("ledger_journals")).isEqualTo(journals);assertThat(number("outbox_events")).isEqualTo(events);
        assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",o.ledger()).query(java.math.BigDecimal.class).single()).isZero();
    }
    @Test void reportAuditFaultAndIncompleteReportCannotCommit() {
        long runs=number("reconciliation_runs"),findings=number("reconciliation_findings"),audit=number("audit_events");
        jdbc.sql("ALTER TABLE banking.audit_events ADD CONSTRAINT test_run_audit_fault CHECK(action<>'RECONCILIATION_COMPLETED') NOT VALID").update();
        try {assertThatThrownBy(()->service.runScheduled()).isInstanceOf(Exception.class);}
        finally {jdbc.sql("ALTER TABLE banking.audit_events DROP CONSTRAINT test_run_audit_fault").update();}
        assertThat(number("reconciliation_runs")).isEqualTo(runs);assertThat(number("reconciliation_findings")).isEqualTo(findings);assertThat(number("audit_events")).isEqualTo(audit);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.reconciliation_runs(id,actor_issuer,actor_subject,actor_type,snapshot_at,grace_seconds,status) VALUES(:id,'fixture','fixture','SYSTEM',now(),300,'RUNNING')").param("id",UUID.randomUUID()).update();return null;
        })).isInstanceOf(Exception.class);
    }
    @Test void rolesPaginationAndVerifiedFinancialAuditUseProductionSecurity() throws Exception {
        var o=owner();String op=operator();
        assertThat(call("POST",ROOT,null,null).statusCode()).isEqualTo(401);assertThat(call("POST",ROOT,o.token(),null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/v1/operations/audit-events",o.token(),null).statusCode()).isEqualTo(403);
        String cashBody="{\"accountId\":\""+o.bank()+"\",\"amount\":\"3\",\"currency\":\"VND\"}";
        var cash=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/operations/cash-ins"))
                .header("Authorization","Bearer "+op).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cashBody)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(cash.statusCode()).as(cash.body()).isEqualTo(201);
        UUID journal=UUID.fromString(json.readTree(cash.body()).path("journalId").asString());
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.audit_events WHERE resource_id=:id AND action='LEDGER_JOURNAL_POSTED'").param("id",journal).query(String.class).single()).isEqualTo("recon-operator");
        for(int n=0;n<3;n++)post(o,true);
        var report=json.readTree(call("POST",ROOT,op,null).body());String id=report.path("run").path("id").asString();
        var first=json.readTree(call("GET",ROOT+"/"+id+"/findings?limit=1",op,null).body());assertThat(first.path("items").size()).isEqualTo(1);
        long cursor=first.path("nextCursor").asLong();assertThat(cursor).isPositive();
        var second=json.readTree(call("GET",ROOT+"/"+id+"/findings?limit=1&afterId="+cursor,op,null).body());
        assertThat(second.path("items").get(0).path("id").asLong()).isGreaterThan(cursor);
        assertThat(call("GET",ROOT+"/"+id+"/findings?limit=201",op,null).statusCode()).isEqualTo(422);
        assertThat(call("GET",ROOT+"/"+UUID.randomUUID(),op,null).statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/v1/operations/audit-events?limit=0",op,null).statusCode()).isEqualTo(422);
        var page=call("GET","/api/v1/operations/audit-events?limit=1",op,null);assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain("token","reason","displayName","requestBody");
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.reconciliation_runs SET status='CLEAN' WHERE id=:id").param("id",UUID.fromString(id)).update()).isInstanceOf(Exception.class);
    }

    @Test void holdsAndProvisioningMarkerMismatchAreDetected() throws Exception {
        var o=owner();var dest=owner();post(o,false);
        String body="{\"accountId\":\""+o.bank()+"\",\"destinationAccountId\":\""+dest.bank()+"\",\"amount\":\"1\",\"expiresAt\":\""+Instant.now().plusSeconds(3600)+"\"}";
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/holds"))
                .header("Authorization","Bearer "+o.token()).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);UUID hold=UUID.fromString(json.readTree(response.body()).path("id").asString());
        mutate("holds",()->jdbc.sql("UPDATE banking.holds SET amount=2 WHERE id=:id").param("id",hold).update());
        mutate("accounts",()->jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=false WHERE id=:id").param("id",o.bank()).update());
        try {assertThat(codes(service.runScheduled())).contains("HOLDS_EXCEED_POSTED","ACCOUNT_LEDGER_LINK_INVALID");}
        finally {
            mutate("accounts",()->jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",o.bank()).update());
            mutate("holds",()->jdbc.sql("UPDATE banking.holds SET amount=1 WHERE id=:id").param("id",hold).update());
            assertThat(call("POST","/api/v1/holds/"+hold+"/release",o.token(),null).statusCode()).isEqualTo(200);
        }
    }
    @Test void findingPersistenceFaultRollsBackReportAndDoesNotRepairMissingEvent() throws Exception {
        var e=post(owner(),false);removeEvent(e);long runs=number("reconciliation_runs");
        jdbc.sql("ALTER TABLE banking.reconciliation_findings ADD CONSTRAINT test_finding_fault CHECK(code<>'OUTBOX_MISSING') NOT VALID").update();
        try {
            assertThatThrownBy(()->service.runScheduled()).isInstanceOf(Exception.class);
            assertThat(number("reconciliation_runs")).isEqualTo(runs);
            assertThat(jdbc.sql("SELECT count(*) FROM banking.outbox_events WHERE aggregate_id=:id").param("id",e.aggregateId()).query(Long.class).single()).isZero();
        } finally {jdbc.sql("ALTER TABLE banking.reconciliation_findings DROP CONSTRAINT test_finding_fault").update();restoreEvent(e);}
    }
    @Test void repeatableSnapshotExcludesLaterCommitAndConcurrentRunIsRejected() throws Exception {
        var o=owner();String op=operator();OutboxEvent[] later={null};
        jdbc.sql("CREATE FUNCTION banking.test_reconciliation_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(1414,99);RETURN NEW;END $$").update();
        jdbc.sql("CREATE TRIGGER test_reconciliation_pause BEFORE INSERT ON banking.reconciliation_runs FOR EACH ROW EXECUTE FUNCTION banking.test_reconciliation_pause()").update();
        try(var blocker=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);try(var s=blocker.createStatement()){s.execute("SELECT pg_advisory_xact_lock(1414,99)");}
            var future=pool.submit(()->call("POST",ROOT,op,null));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event='advisory' AND query LIKE '%INSERT INTO banking.reconciliation_runs%')").query(Boolean.class).single();
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).isTrue();assertThat(call("POST",ROOT,op,null).statusCode()).isEqualTo(409);
                later[0]=post(o,false);removeEvent(later[0]);
            } finally {blocker.commit();}
            var first=future.get(20,TimeUnit.SECONDS);assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
            assertThat(json.readTree(first.body()).path("run").path("status").asString()).isEqualTo("CLEAN");
            var next=service.runScheduled();assertThat(codes(next)).contains("OUTBOX_MISSING");
        } finally {
            jdbc.sql("DROP TRIGGER test_reconciliation_pause ON banking.reconciliation_runs").update();jdbc.sql("DROP FUNCTION banking.test_reconciliation_pause()").update();
            if(later[0]!=null)restoreEvent(later[0]);
        }
    }

    @Test void successfulRetryLeavesHistoryButDoesNotProduceFalseBacklogWarning() throws Exception {
        var e=post(owner(),false);
        jdbc.sql("INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,error_code) VALUES(:topic,0,0,:hash,1,clock_timestamp()-interval '2 days','PROCESSING_FAILED')")
                .param("topic","fixture-"+e.eventId()).param("hash","a".repeat(64)).update();
        assertThat(codes(service.runScheduled())).contains("NOTIFICATION_RETRY_LAG");
        accept(e);
        assertThat(service.runScheduled().run().status()).isEqualTo("CLEAN");
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_failures WHERE source_topic=:topic").param("topic","fixture-"+e.eventId()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void sourceReceiptFailureRollsBackInboxAndNotificationThenRetryIsSafe() throws Exception {
        var o=owner();var e=post(o,false);
        jdbc.sql("ALTER TABLE banking.notification_processed_sources ADD CONSTRAINT test_source_fault CHECK(event_id<>'"+e.eventId()+"'::uuid)").update();
        try {assertThatThrownBy(()->accept(e)).isInstanceOf(Exception.class);}
        finally {jdbc.sql("ALTER TABLE banking.notification_processed_sources DROP CONSTRAINT test_source_fault").update();}
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_inbox WHERE event_id=:id").param("id",e.eventId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notifications WHERE event_id=:id").param("id",e.eventId()).query(Long.class).single()).isZero();
        accept(e);accept(e);
        notifications.accept(new SourceRecord("replay-"+e.eventId(),0,0,e.aggregateId().toString(),json.writeValueAsString(e)));
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notifications WHERE event_id=:id").param("id",e.eventId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_processed_sources WHERE event_id=:id").param("id",e.eventId()).query(Long.class).single()).isEqualTo(2);
    }

    private void removeEvent(OutboxEvent e){
        mutate("outbox_deliveries",()->jdbc.sql("DELETE FROM banking.outbox_deliveries WHERE event_id=:id").param("id",e.eventId()).update());
        mutate("outbox_events",()->jdbc.sql("DELETE FROM banking.outbox_events WHERE event_id=:id").param("id",e.eventId()).update());
    }
    private void restoreEvent(OutboxEvent e){
        jdbc.sql("INSERT INTO banking.outbox_events(event_id,aggregate_id,aggregate_type,aggregate_version,event_type,schema_version,occurred_at,payload) VALUES(:id,:aggregate,:type,:version,:event,:schema,:time,CAST(:payload AS JSONB))")
                .param("id",e.eventId()).param("aggregate",e.aggregateId()).param("type",e.aggregateType()).param("version",e.aggregateVersion()).param("event",e.eventType())
                .param("schema",e.schemaVersion()).param("time",Timestamp.from(e.occurredAt())).param("payload",e.payload()).update();restoreDelivery(e);
    }

    private List<String> codes(ReconciliationService.Report r){return r.summary().stream().map(ReconciliationRepository.RuleCount::code).toList();}
    private long number(String table){return jdbc.sql("SELECT count(*) FROM banking."+table).query(Long.class).single();}
    private void mutate(String table,Runnable change){
        jdbc.sql("ALTER TABLE banking."+table+" DISABLE TRIGGER USER").update();
        try {change.run();}finally {jdbc.sql("ALTER TABLE banking."+table+" ENABLE TRIGGER USER").update();}
    }
    private void restoreDelivery(OutboxEvent e){jdbc.sql("INSERT INTO banking.outbox_deliveries(event_id) VALUES(:id)").param("id",e.eventId()).update();}
    private OutboxEvent event(UUID id){return new TransactionTemplate(transactions).execute(s->outbox.find(id).orElseThrow());}
    private void accept(OutboxEvent e){notifications.accept(new SourceRecord("fixture-"+e.eventId(),0,0,e.aggregateId().toString(),json.writeValueAsString(e)));}
    private String operator() throws Exception{return TOKENS.token(b->b.subject("recon-operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));}
    private HttpResponse<String> call(String method,String path,String token,String body) throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(40));
        if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Owner owner() throws Exception {
        UUID customer=UUID.randomUUID(),bank=UUID.randomUUID(),ledger=UUID.randomUUID();
        new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic Reconciliation',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",bank).param("customer",customer).update();
            jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic Reconciliation','LIABILITY','CREDIT','VND',:bank,now())").param("id",ledger).param("code",ledger.toString()).param("bank",bank).update();
            jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",bank).update();return null;
        });return new Owner(bank,ledger,TOKENS.token(b->b.subject(customer.toString())));
    }
    private OutboxEvent post(Owner o,boolean old){
        UUID journal=UUID.randomUUID();return new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','fixture','fixture-operator',clock_timestamp()-make_interval(days=>:days))")
                    .param("id",journal).param("ref",UUID.randomUUID()).param("days",old?2:0).update();
            for(var entry:Map.of(CLEARING,"DEBIT",o.ledger(),"CREDIT").entrySet())
                jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:journal,:ledger,'VND',:side,1)")
                        .param("id",UUID.randomUUID()).param("journal",journal).param("ledger",entry.getKey()).param("side",entry.getValue()).update();
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=created_at WHERE id=:id").param("id",journal).update();
            UUID event=jdbc.sql("SELECT event_id FROM banking.outbox_events WHERE aggregate_id=:id").param("id",journal).query(UUID.class).single();return outbox.find(event).orElseThrow();
        });
    }
}
