package vn.banklab.core.external;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.banklab.core.support.SignedTokenServer;
import vn.banklab.partnersimulator.PartnerSimulator;
import vn.banklab.risksimulator.AmountRiskService;
import vn.banklab.risk.v1.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExternalPaymentIT {
    static final UUID SETTLEMENT=UUID.randomUUID();
    static final SignedTokenServer TOKENS;
    static final Proxy PARTNER;
    static final Server RISK;
    static final AtomicBoolean RISK_DOWN=new AtomicBoolean();
    static {try{
        TOKENS=new SignedTokenServer();PARTNER=new Proxy();
        RISK=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new RiskEvaluatorGrpc.RiskEvaluatorImplBase(){
            public void assessTransfer(AssessTransferRequest r,StreamObserver<AssessTransferResponse> reply){
                if(RISK_DOWN.get())reply.onError(Status.UNAVAILABLE.asRuntimeException());
                else new AmountRiskService("99999999999999999999").assessTransfer(r,reply);
            }
        }).build().start();
    }catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri",TOKENS::issuer);r.add("bank.iam.jwk-set-uri",TOKENS::jwks);r.add("bank.iam.audience",()->"bank-api");
        r.add("bank.external.enabled",()->true);r.add("bank.external.port",PARTNER::port);r.add("bank.external.settlement-account",SETTLEMENT::toString);r.add("bank.external.timeout-millis",()->1000);
        r.add("bank.risk.enabled",()->true);r.add("bank.risk.port",RISK::getPort);r.add("bank.risk.deadline-millis",()->1500);
    }
    @AfterAll static void closeResources()throws Exception{PARTNER.close();RISK.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);TOKENS.close();}
    @BeforeEach void setup()throws Exception{
        PARTNER.reset();RISK_DOWN.set(false);
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM banking.accounts WHERE id=:id)").param("id",SETTLEMENT).query(Boolean.class).single())owner(SETTLEMENT);
    }
    @AfterEach void release(){PARTNER.release.countDown();}
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newHttpClient();
    private final JsonMapper json=JsonMapper.builder().build();
    private record Owner(UUID account,String token) { }
    private final String root="/api/v1/external-payments";

    @Test void prepareReservesGrossAndKeyReplaySkipsFailedRiskAndPartner()throws Exception{
        var a=owner();cash(a,"100");policy(a,2,500,500);
        String key=UUID.randomUUID().toString();var p=create(a,key,"30");assertThat(p.path("state").asString()).isEqualTo("PREPARED");
        assertThat(p.path("reservedAmount").asString()).isEqualTo("32");assertThat(p.path("riskOutcome").asString()).isEqualTo("APPROVED");
        balance(a,"100","32","68");int calls=PARTNER.calls.get();RISK_DOWN.set(true);PARTNER.mode="UNAVAILABLE";
        var replay=call("POST",root,a.token(),key,body(a,"30"));assertThat(replay.statusCode()).isEqualTo(201);assertThat(tree(replay)).isEqualTo(p);assertThat(PARTNER.calls).hasValue(calls);
        assertThat(call("POST",root,a.token(),key,body(a,"31")).statusCode()).isEqualTo(409);
        assertThat(PARTNER.puts).hasValue(0);assertThat(journals(a)).isEqualTo(1);
    }
    @Test void everyEndpointChecksIdentityAndPrivacyBeforeNetwork()throws Exception{
        var a=owner();var b=owner();cash(a,"100");var p=create(a,"10");String id=p.path("id").asString();int calls=PARTNER.calls.get();
        for(String suffix:List.of("","/history","/dispatch","/reconcile","/cancel")){
            String method=suffix.isEmpty()||suffix.equals("/history")?"GET":"POST";
            assertThat(call(method,root+"/"+id+suffix,null,null,null).statusCode()).isEqualTo(401);
            assertThat(call(method,root+"/"+id+suffix,operator(),null,null).statusCode()).isEqualTo(403);
            assertThat(call(method,root+"/"+id+suffix,b.token(),null,null).statusCode()).isEqualTo(404);
        }
        assertThat(call("POST",root,b.token(),UUID.randomUUID().toString(),body(a,"1")).statusCode()).isEqualTo(404);
        assertThat(PARTNER.calls).hasValue(calls);
        var status=call("GET",root+"/"+id,a.token(),null,null);assertThat(status.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(status.body()).doesNotContain("requestHash","actor_issuer","idempotency",a.token(),SETTLEMENT.toString());
    }
    @Test void unknownBlocksAllOtherDebitsHoldsAndCancellationWithoutExpiry()throws Exception{
        var a=owner();var b=owner();cash(a,"100");var p=create(a,"80");String id=p.path("id").asString();
        assertThat(post(a,id,"dispatch").statusCode()).isEqualTo(202);balance(a,"100","80","20");
        assertThat(transfer(a,b,"21").statusCode()).isEqualTo(422);
        var hold=call("POST","/api/v1/holds",a.token(),UUID.randomUUID().toString(),"{\"accountId\":\""+a.account()+"\",\"destinationAccountId\":\""+b.account()+"\",\"amount\":\"21\",\"expiresAt\":\""+Instant.now().plusSeconds(60)+"\"}");
        assertThat(hold.statusCode()).isEqualTo(422);assertThat(post(a,id,"cancel").statusCode()).isEqualTo(409);
        assertThat(call("POST","/api/v1/accounts/"+a.account()+"/close",a.token(),null,"{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}").statusCode()).isEqualTo(422);
        PARTNER.mode="NOT_FOUND";assertThat(post(a,id,"dispatch").statusCode()).isEqualTo(202);assertThat(PARTNER.puts).hasValue(1);balance(a,"100","80","20");
        assertThat(jdbc.sql("SELECT banking.reserved_balance(:id)").param("id",a.account()).query(String.class).single()).isEqualTo("80");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{
            UUID journal=UUID.randomUUID();
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','fixture','external-sql',now())").param("id",journal).param("ref",UUID.randomUUID()).update();
            UUID source=jdbc.sql("SELECT id FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id",a.account()).query(UUID.class).single();
            for(var line:List.of(Map.entry(source,"DEBIT"),Map.entry(UUID.fromString("00000000-0000-0000-0000-000000000001"),"CREDIT")))
                jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:j,:l,'VND',:side,21)").param("id",UUID.randomUUID()).param("j",journal).param("l",line.getKey()).param("side",line.getValue()).update();
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id=:id").param("id",journal).update();return null;
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(journals(a)).isEqualTo(1);
    }
    @Test void concurrentDispatchAndSuccessReconciliationPostOnceWithAtomicFeeAndAudit()throws Exception{
        var a=owner();cash(a,"100");policy(a,2,500,500);String id=create(a,"30").path("id").asString();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var work=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<8;i++)work.add(pool.submit(()->{start.await();return post(a,id,"dispatch");}));start.countDown();
            for(var f:work)assertThat(f.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(PARTNER.puts).hasValue(1);PARTNER.finish(id,"SUCCEEDED");work.clear();
            for(int i=0;i<8;i++)work.add(pool.submit(()->post(a,id,"reconcile")));String journal=null;
            for(var f:work){var r=f.get(10,TimeUnit.SECONDS);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);String next=tree(r).path("journalId").asString();if(journal==null)journal=next;else assertThat(next).isEqualTo(journal);}
            assertThat(journals(a)).isEqualTo(3);balance(a,"68","0","68");
            UUID j=UUID.fromString(journal);assertThat(jdbc.sql("SELECT fee FROM banking.outgoing_charges WHERE journal_id=:id").param("id",j).query(String.class).single()).isEqualTo("2");
            assertThat(jdbc.sql("SELECT count(*) FROM banking.outbox_events WHERE aggregate_id=:id").param("id",j).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM banking.audit_events WHERE resource_id=:id").param("id",j).query(Long.class).single()).isEqualTo(1);
        }
    }
    @Test void definitiveFailureReleasesAndPreparedCancelSerializesWithDispatch()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"60").path("id").asString();post(a,id,"dispatch");PARTNER.finish(id,"FAILED");
        var failed=post(a,id,"reconcile");assertThat(tree(failed).path("state").asString()).isEqualTo("FAILED");balance(a,"100","0","100");assertThat(journals(a)).isEqualTo(1);
        String cancel=create(a,"20").path("id").asString();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var x=pool.submit(()->{start.await();return post(a,cancel,"cancel");});var y=pool.submit(()->{start.await();return post(a,cancel,"dispatch");});start.countDown();x.get(5,TimeUnit.SECONDS);y.get(5,TimeUnit.SECONDS);
        }
        var current=tree(call("GET",root+"/"+cancel,a.token(),null,null));
        if(current.path("state").asString().equals("CANCELLED")){assertThat(PARTNER.get(cancel).statusCode()).isEqualTo(404);balance(a,"100","0","100");}
        else{assertThat(current.path("state").asString()).isEqualTo("UNKNOWN");balance(a,"100","20","80");assertThat(post(a,cancel,"cancel").statusCode()).isEqualTo(409);}
    }
    @Test void frozenOrStaleQuoteBeforeDispatchNeverSubmitsAndRemainsCancellable()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"30").path("id").asString();
        assertThat(freeze(a,0,true).statusCode()).isEqualTo(200);assertThat(post(a,id,"dispatch").statusCode()).isEqualTo(409);assertThat(PARTNER.puts).hasValue(0);
        assertThat(freeze(a,1,false).statusCode()).isEqualTo(200);policy(a,2,500,500);
        var stale=post(a,id,"dispatch");assertThat(stale.statusCode()).isEqualTo(409);assertThat(tree(stale).path("code").asString()).isEqualTo("EXTERNAL_RESERVATION_STALE");
        assertThat(PARTNER.puts).hasValue(0);assertThat(post(a,id,"cancel").statusCode()).isEqualTo(200);balance(a,"100","0","100");
    }
    @Test void lostSubmissionResponseRetainsReservationAndNeverResubmits()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"25").path("id").asString();PARTNER.mode="PAUSE_PUT";
        var r=post(a,id,"dispatch");assertThat(r.statusCode()).isEqualTo(202);assertThat(tree(r).path("problem").asString()).isEqualTo("EXTERNAL_PARTNER_UNAVAILABLE");
        PARTNER.release.countDown();PARTNER.mode="PASS";assertThat(PARTNER.get(id).statusCode()).isEqualTo(200);balance(a,"100","25","75");
        PARTNER.finish(id,"SUCCEEDED");assertThat(post(a,id,"dispatch").statusCode()).isEqualTo(200);assertThat(PARTNER.puts).hasValue(1);balance(a,"75","0","75");
        assertThat(PARTNER.authorization.get()).isNull();var wire=json.readTree(PARTNER.lastPut.get());assertThat(new HashSet<>(wire.propertyNames())).containsExactlyInAnyOrder("requestHash","amount","currency","beneficiary");
        assertThat(PARTNER.lastPut.get()).doesNotContain(a.account().toString(),a.token(),SETTLEMENT.toString());
    }
    @Test void zeroBalanceClearingAccountCannotCloseWithPendingExternalObligations()throws Exception{
        String subject=jdbc.sql("SELECT c.identity_subject FROM banking.accounts a JOIN banking.customers c ON c.id=a.customer_id WHERE a.id=:id").param("id",SETTLEMENT).query(String.class).single();
        var clearing=new Owner(SETTLEMENT,TOKENS.token(c->c.subject(subject)));
        String posted=jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id",SETTLEMENT).query(java.math.BigDecimal.class).single().setScale(0).toPlainString();
        if(!posted.equals("0")){var recipient=owner();assertThat(transfer(clearing,recipient,posted).statusCode()).isEqualTo(201);}
        var source=owner();cash(source,"100");create(source,"30");balance(clearing,"0","0","0");
        var close=call("POST","/api/v1/accounts/"+SETTLEMENT+"/close",clearing.token(),null,"{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}");
        assertThat(close.statusCode()).as(close.body()).isEqualTo(422);assertThat(tree(close).path("code").asString()).isEqualTo("ACCOUNT_CLOSURE_UNAVAILABLE");
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.accounts SET status='CLOSED',version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",SETTLEMENT).update())
                .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,e->assertThat(sqlState(e)).isEqualTo("23514"));
    }
    @Test void staleInFlightResponseCannotUndoLaterSuccessfulSettlement()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"10").path("id").asString();PARTNER.mode="PAUSE_PUT";
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var delayed=pool.submit(()->post(a,id,"dispatch"));assertThat(PARTNER.accepted.await(5,TimeUnit.SECONDS)).isTrue();
            PARTNER.finish(id,"SUCCEEDED");var newer=post(a,id,"reconcile");assertThat(newer.statusCode()).isEqualTo(200);
            PARTNER.release.countDown();var older=delayed.get(5,TimeUnit.SECONDS);assertThat(older.statusCode()).isEqualTo(200);assertThat(tree(older).path("journalId")).isEqualTo(tree(newer).path("journalId"));
        }finally{PARTNER.release.countDown();}
        balance(a,"90","0","90");assertThat(journals(a)).isEqualTo(2);
    }
    @Test void wrongBindingMalformedRedirectAndMissingEvidenceCannotReleaseMoney()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"30").path("id").asString();post(a,id,"dispatch");
        for(String mode:List.of("WRONG_EPOCH","WRONG_AMOUNT","MALFORMED","NOT_FOUND","REDIRECT")){
            PARTNER.mode=mode;var r=post(a,id,"reconcile");assertThat(r.statusCode()).as(r.body()).isEqualTo(202);assertThat(tree(r).path("state").asString()).isEqualTo("UNKNOWN");balance(a,"100","30","70");
        }
        assertThat(PARTNER.redirectHits).hasValue(0);assertThat(journals(a)).isEqualTo(1);
    }
    @Test void restartPreconditionRejectsNewEpochWithoutCreatingAnotherPartnerOperation()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"40").path("id").asString();PARTNER.restart();
        var r=post(a,id,"dispatch");assertThat(r.statusCode()).isEqualTo(202);assertThat(tree(r).path("problem").asString()).isEqualTo("EXTERNAL_PARTNER_INSTANCE_CHANGED");
        assertThat(PARTNER.get(id).statusCode()).isEqualTo(404);post(a,id,"dispatch");assertThat(PARTNER.puts).hasValue(1);balance(a,"100","40","60");
    }
    @Test void frozenOrChangedPolicyRetainsVerifiedSuccessUntilSettlementCanProceed()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"30").path("id").asString();post(a,id,"dispatch");PARTNER.finish(id,"SUCCEEDED");
        assertThat(freeze(a,0,true).statusCode()).isEqualTo(200);var blocked=post(a,id,"reconcile");assertThat(tree(blocked).path("problem").asString()).isEqualTo("EXTERNAL_SETTLEMENT_BLOCKED");
        assertThat(tree(blocked).path("partnerState").asString()).isEqualTo("SUCCEEDED");PARTNER.mode="FALSE_FAILED";
        var conflict=post(a,id,"reconcile");assertThat(tree(conflict).path("problem").asString()).isEqualTo("EXTERNAL_PARTNER_CONFLICT");balance(a,"100","30","70");
        assertThat(freeze(a,1,false).statusCode()).isEqualTo(200);PARTNER.mode="PASS";policy(a,80,500,500);
        assertThat(post(a,id,"reconcile").statusCode()).isEqualTo(202);balance(a,"100","30","70");
        jdbc.sql("UPDATE banking.outgoing_policies SET fee=2 WHERE account_id=:id").param("id",a.account()).update();
        assertThat(post(a,id,"reconcile").statusCode()).isEqualTo(200);balance(a,"68","0","68");
    }
    @Test void settlementFaultRollsBackFeeUsageOutboxAuditAndRecoversFromDurableUnknown()throws Exception{
        var a=owner();cash(a,"100");policy(a,2,500,500);String id=create(a,"30").path("id").asString();post(a,id,"dispatch");PARTNER.finish(id,"SUCCEEDED");
        long events=count("banking.outbox_events"),audit=count("banking.audit_events");
        jdbc.sql("CREATE FUNCTION banking.test_external_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='SUCCEEDED' THEN RAISE EXCEPTION 'Synthetic settlement fault' USING ERRCODE='23514'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER test_external_fault BEFORE UPDATE ON banking.external_payments FOR EACH ROW EXECUTE FUNCTION banking.test_external_fault()").update();
        try{assertThat(post(a,id,"reconcile").statusCode()).isEqualTo(202);}finally{jdbc.sql("DROP TRIGGER test_external_fault ON banking.external_payments").update();jdbc.sql("DROP FUNCTION banking.test_external_fault()").update();}
        balance(a,"100","32","68");assertThat(journals(a)).isEqualTo(1);assertThat(count("banking.outbox_events")).isEqualTo(events);assertThat(count("banking.audit_events")).isEqualTo(audit);
        assertThat(post(a,id,"reconcile").statusCode()).isEqualTo(200);balance(a,"68","0","68");
    }
    @Test void exactTwentyDigitSettlementAndHistoryAreOwnedImmutableAndBounded()throws Exception{
        var a=owner();String amount="10000000000000000000";cash(a,amount);String id=create(a,amount).path("id").asString();post(a,id,"dispatch");PARTNER.finish(id,"SUCCEEDED");
        var settled=post(a,id,"reconcile");assertThat(tree(settled).path("amount").asString()).isEqualTo(amount);balance(a,"0","0","0");
        var history=call("GET",root+"/"+id+"/history?limit=100",a.token(),null,null);assertThat(history.statusCode()).isEqualTo(200);assertThat(tree(history).get(0).path("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(call("GET",root+"/"+id+"/history?limit=101",a.token(),null,null).statusCode()).isEqualTo(422);
        assertThatThrownBy(()->jdbc.sql("DELETE FROM banking.external_payment_history WHERE payment_id=:id").param("id",UUID.fromString(id)).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.external_payments SET amount=1 WHERE id=:id").param("id",UUID.fromString(id)).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void riskFailureDoesNotPrepareAndNetworkPreflightHoldsNoDatabaseTransaction()throws Exception{
        var a=owner();cash(a,"100");RISK_DOWN.set(true);var rejected=call("POST",root,a.token(),UUID.randomUUID().toString(),body(a,"1"));assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.external_payments WHERE account_id=:id").param("id",a.account()).query(Long.class).single()).isZero();RISK_DOWN.set(false);
        PARTNER.mode="PAUSE_HEALTH";
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var waiting=pool.submit(()->call("POST",root,a.token(),UUID.randomUUID().toString(),body(a,"1")));assertThat(PARTNER.accepted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(freeze(a,0,true).statusCode()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'").query(Long.class).single()).isZero();
            PARTNER.release.countDown();assertThat(waiting.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }finally{PARTNER.release.countDown();}
    }
    @Test void validationSwaggerAndSseReflectDurableReservationChanges()throws Exception{
        var a=owner();cash(a,"100");
        var invalid=call("POST",root,a.token(),UUID.randomUUID().toString(),body(a,"1").replace("\"amount\":\"1\"","\"amount\":1"));assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(call("POST",root,a.token(),UUID.randomUUID().toString(),body(a,"1").replace("SIM_DESTINATION_1","real-name")).statusCode()).isEqualTo(400);
        int calls=PARTNER.calls.get();assertThat(call("POST",root,a.token(),UUID.randomUUID().toString(),body(a,"0")).statusCode()).isEqualTo(400);assertThat(PARTNER.calls).hasValue(calls);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/accounts/"+a.account()+"/events")).header("Authorization","Bearer "+a.token()).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        var input=response.body();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
          try{
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
            assertThat(pool.submit(()->nextSse(reader)).get(5,TimeUnit.SECONDS)).contains("CONNECTED");
            create(a,"10");assertThat(pool.submit(()->nextSse(reader)).get(5,TimeUnit.SECONDS)).contains("STATE_CHANGED");
          }finally{input.close();}
        }
        var docs=tree(call("GET","/v3/api-docs",null,null,null));
        assertThat(docs.path("paths").path(root).path("post").path("responses").path("503").path("content").has("application/problem+json")).isTrue();
        assertThat(docs.path("paths").path(root+"/{id}/reconcile").path("post").path("operationId").asString()).isEqualTo("reconcileMyExternalPayment");
    }
    @Test void sqlRejectsIncompleteCommandsFakeSettlementAndForgedHistory()throws Exception{
        var a=owner();cash(a,"100");String id=create(a,"20").path("id").asString();
        assertThatThrownBy(()->jdbc.sql("""
                INSERT INTO banking.external_payments(id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,settlement_account_id,
                    beneficiary,amount,reserved_amount,partner_instance,risk_outcome,risk_policy,risk_reason,risk_assessed_at,risk_expires_at,state)
                SELECT :new,actor_issuer,actor_subject,:key,request_hash,account_id,settlement_account_id,beneficiary,amount,amount,partner_instance,
                    risk_outcome,risk_policy,risk_reason,risk_assessed_at,risk_expires_at,'CREATING' FROM banking.external_payments WHERE id=:id
                """).param("new",UUID.randomUUID()).param("key",UUID.randomUUID().toString()).param("id",UUID.fromString(id)).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID cash=jdbc.sql("SELECT p.journal_id FROM banking.ledger_postings p JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id WHERE l.bank_account_id=:id LIMIT 1").param("id",a.account()).query(UUID.class).single();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("UPDATE banking.external_payments SET state='UNKNOWN',version=version+1 WHERE id=:id").param("id",UUID.fromString(id)).update();
            jdbc.sql("UPDATE banking.external_payments SET state='SETTLING',partner_version=2,partner_state='SUCCEEDED',version=version+1 WHERE id=:id").param("id",UUID.fromString(id)).update();
            jdbc.sql("UPDATE banking.external_payments SET state='SUCCEEDED',journal_id=:journal,version=version+1 WHERE id=:id").param("id",UUID.fromString(id)).param("journal",cash).update();return null;
        })).isInstanceOfSatisfying(RuntimeException.class,e->assertThat(sqlState(e)).isEqualTo("23514"));
        balance(a,"100","20","80");assertThat(tree(call("GET",root+"/"+id,a.token(),null,null)).path("state").asString()).isEqualTo("PREPARED");
        assertThatThrownBy(()->jdbc.sql("INSERT INTO banking.external_payment_history(payment_id,version,state,partner_version) VALUES(:id,999,'FAILED',0)").param("id",UUID.fromString(id)).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void reconciliationDetectsExternalReservationMismatchInCorruptedFixture()throws Exception{
        var a=owner();cash(a,"100");create(a,"80");
        // Disposable DB fault injection only: bypass one guard to prove the diagnostic sees external holds.
        new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("ALTER TABLE banking.ledger_journals DISABLE TRIGGER ledger_available_validate").update();
            UUID journal=UUID.randomUUID(),source=jdbc.sql("SELECT id FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id",a.account()).query(UUID.class).single();
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','fixture','external-corruption',now())").param("id",journal).param("ref",UUID.randomUUID()).update();
            for(var line:List.of(Map.entry(source,"DEBIT"),Map.entry(UUID.fromString("00000000-0000-0000-0000-000000000001"),"CREDIT")))
                jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:j,:l,'VND',:side,90)").param("id",UUID.randomUUID()).param("j",journal).param("l",line.getKey()).param("side",line.getValue()).update();
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id=:id").param("id",journal).update();
            jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
            jdbc.sql("ALTER TABLE banking.ledger_journals ENABLE TRIGGER ledger_available_validate").update();return null;
        });
        assertThat(jdbc.sql("SELECT count(*) FROM banking.reconciliation_scan(transaction_timestamp(),300) WHERE code='HOLDS_EXCEED_POSTED' AND resource_id=:id").param("id",a.account().toString()).query(Long.class).single()).isEqualTo(1);
    }
    private String nextSse(java.io.BufferedReader reader)throws Exception{
        String line;var frame=new StringBuilder();while((line=reader.readLine())!=null){if(line.isEmpty()){if(frame.toString().contains("event:resync"))return frame.toString();frame.setLength(0);}else frame.append(line).append('\n');}throw new AssertionError("Missing SSE event");
    }
    private String sqlState(Throwable error){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql)return sql.getSQLState();return null;}
    private JsonNode create(Owner a,String amount)throws Exception{return create(a,UUID.randomUUID().toString(),amount);}
    private JsonNode create(Owner a,String key,String amount)throws Exception{var r=call("POST",root,a.token(),key,body(a,amount));assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return tree(r);}
    private String body(Owner a,String amount){return "{\"accountId\":\""+a.account()+"\",\"beneficiary\":\"SIM_DESTINATION_1\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}";}
    private HttpResponse<String> post(Owner a,String id,String action)throws Exception{return call("POST",root+"/"+id+"/"+action,a.token(),null,null);}
    private JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    private void balance(Owner a,String posted,String reserved,String available)throws Exception{var r=call("GET","/api/v1/ledger/accounts/"+a.account()+"/balance",a.token(),null,null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var b=tree(r);assertThat(b.path("postedBalance").asString()).isEqualTo(posted);assertThat(b.path("reservedBalance").asString()).isEqualTo(reserved);assertThat(b.path("availableBalance").asString()).isEqualTo(available);}
    private long count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single();}
    private long journals(Owner a){return jdbc.sql("SELECT count(DISTINCT p.journal_id) FROM banking.ledger_postings p JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id WHERE l.bank_account_id=:id").param("id",a.account()).query(Long.class).single();}
    private void policy(Owner a,int fee,int transaction,int daily){jdbc.sql("INSERT INTO banking.outgoing_policies(account_id,transaction_limit,daily_limit,fee,timezone) VALUES(:id,:tx,:daily,:fee,'UTC')").param("id",a.account()).param("tx",transaction).param("daily",daily).param("fee",fee).update();}
    private HttpResponse<String> freeze(Owner a,int version,boolean frozen)throws Exception{return call("POST","/api/v1/operations/accounts/"+a.account()+"/"+(frozen?"freeze":"unfreeze"),operator(),null,"{\"expectedVersion\":"+version+",\"reason\":\""+(frozen?"SECURITY_REVIEW":"REVIEW_CLEARED")+"\"}");}
    private HttpResponse<String> transfer(Owner a,Owner b,String amount)throws Exception{return call("POST","/api/v1/transfers",a.token(),UUID.randomUUID().toString(),"{\"sourceAccountId\":\""+a.account()+"\",\"destinationAccountId\":\""+b.account()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}");}
    private String operator()throws Exception{return TOKENS.token(c->c.subject("external-operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));}
    private void cash(Owner a,String amount)throws Exception{var r=call("POST","/api/v1/operations/cash-ins",operator(),UUID.randomUUID().toString(),"{\"accountId\":\""+a.account()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}");assertThat(r.statusCode()).as(r.body()).isEqualTo(201);}
    private HttpResponse<String> call(String method,String path,String token,String key,String body)throws Exception{var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");return vn.banklab.core.support.ApprovedOperations.send(http,r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),TOKENS);}
    private Owner owner()throws Exception{return owner(UUID.randomUUID());}
    private Owner owner(UUID account)throws Exception{
        UUID customer=UUID.randomUUID(),ledger=UUID.randomUUID();new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic External',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",account).param("customer",customer).update();
            jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic External','LIABILITY','CREDIT','VND',:bank,now())")
                    .param("id",ledger).param("code",ledger.toString()).param("bank",account).update();jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",account).update();return null;});
        return new Owner(account,TOKENS.token(c->c.subject(customer.toString())));
    }
    private static final class Proxy implements AutoCloseable {
        final HttpServer proxy;final ExecutorService workers=Executors.newFixedThreadPool(8);volatile PartnerSimulator actual;
        final HttpClient http=HttpClient.newHttpClient();final JsonMapper json=JsonMapper.builder().build();
        volatile String mode="PASS";volatile CountDownLatch accepted=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger puts=new AtomicInteger(),calls=new AtomicInteger(),redirectHits=new AtomicInteger();
        final AtomicReference<String> lastPut=new AtomicReference<>(),authorization=new AtomicReference<>();
        Proxy()throws Exception{actual=new PartnerSimulator(0,true,10000,100);actual.start();proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);proxy.setExecutor(workers);proxy.createContext("/",exchange->{
            try{
                calls.incrementAndGet();String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod(),current=mode;
                if(path.equals("/redirect-target")){redirectHits.incrementAndGet();exchange.sendResponseHeaders(500,-1);return;}
                if(method.equals("GET")&&path.startsWith("/payments/")){
                    if(current.equals("NOT_FOUND")){exchange.sendResponseHeaders(404,-1);return;}
                    if(current.equals("UNAVAILABLE")){exchange.sendResponseHeaders(503,-1);return;}
                    if(current.equals("REDIRECT")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+port()+"/redirect-target");exchange.sendResponseHeaders(302,-1);return;}
                    if(current.equals("STALE"))path+="?version=1";
                }
                byte[] body=exchange.getRequestBody().readAllBytes();var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+actual.port()+path)).timeout(Duration.ofSeconds(5));
                if(method.equals("PUT")){puts.incrementAndGet();lastPut.set(new String(body,java.nio.charset.StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));r.header("If-Partner-Instance",exchange.getRequestHeaders().getFirst("If-Partner-Instance"));}
                if(body.length>0)r.header("Content-Type","application/json");
                var response=http.send(r.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
                String output=response.body();
                if(method.equals("GET")&&path.startsWith("/payments/")&&response.statusCode()==200){
                    if(current.equals("MALFORMED"))output="secret-not-json";
                    else{var node=(ObjectNode)json.readTree(output);if(current.equals("WRONG_EPOCH"))node.put("instanceId",UUID.randomUUID().toString());if(current.equals("WRONG_AMOUNT"))node.put("amount","999");if(current.equals("FALSE_FAILED"))node.put("state","FAILED");output=json.writeValueAsString(node);}
                }
                if((current.equals("PAUSE_PUT")&&method.equals("PUT"))||(current.equals("PAUSE_HEALTH")&&path.equals("/health"))){accepted.countDown();release.await(5,TimeUnit.SECONDS);}
                byte[] bytes=output.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception ignored){}finally{exchange.close();}
        });proxy.start();}
        int port(){return proxy.getAddress().getPort();}
        void reset(){release.countDown();mode="PASS";puts.set(0);calls.set(0);redirectHits.set(0);accepted=new CountDownLatch(1);release=new CountDownLatch(1);}
        void restart()throws Exception{actual.close();actual=new PartnerSimulator(0,true,10000,100);actual.start();}
        HttpResponse<String> get(String id)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+actual.port()+"/payments/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void finish(String id,String state)throws Exception{var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+actual.port()+"/test/payments/"+id)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"state\":\""+state+"\"}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);}
        public void close(){release.countDown();proxy.stop(0);workers.shutdownNow();actual.close();}
    }
}
