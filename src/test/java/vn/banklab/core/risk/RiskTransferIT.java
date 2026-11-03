package vn.banklab.core.risk;

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
import vn.banklab.core.support.SignedTokenServer;
import vn.banklab.risk.v1.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskTransferIT {
    enum Mode { APPROVE, REJECT, UNAVAILABLE, WRONG_ID, UNKNOWN, LATE, PAUSE }
    static final SignedTokenServer TOKENS;
    static final RiskServer SERVICE=new RiskServer();
    static final Server GRPC;
    static {try {TOKENS=new SignedTokenServer();GRPC=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(SERVICE).build().start();}
        catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri",TOKENS::issuer);r.add("bank.iam.jwk-set-uri",TOKENS::jwks);r.add("bank.iam.audience",()->"bank-api");
        r.add("bank.risk.enabled",()->true);r.add("bank.risk.port",GRPC::getPort);r.add("bank.risk.deadline-millis",()->1500);r.add("bank.risk.approval-millis",()->500);
    }
    @AfterAll static void closeServers()throws Exception{GRPC.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);TOKENS.close();}
    @BeforeEach void reset(){SERVICE.mode.set(Mode.APPROVE);SERVICE.calls.set(0);SERVICE.seen.clear();SERVICE.entered=new CountDownLatch(1);SERVICE.release=new CountDownLatch(1);SERVICE.lateDone=new CountDownLatch(1);}
    @AfterEach void unblock(){SERVICE.release.countDown();}
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newHttpClient();
    private final JsonMapper json=JsonMapper.builder().build();
    private record Owner(UUID account,String subject,String token) { }

    @Test void approvalCommitsOwnedImmutableDecisionAndExactTwentyDigitAmountWithoutIdentityOnWire()throws Exception{
        var a=owner();var b=owner();String amount="99999999999999999999";cash(a,amount);
        var result=transfer(a,b,UUID.randomUUID().toString(),amount);assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        String id=json.readTree(result.body()).path("id").asString();
        var response=call("GET","/api/v1/transfers/"+id+"/risk-decision",a.token(),null,null);
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var receipt=json.readTree(response.body());assertThat(receipt.path("outcome").asString()).isEqualTo("APPROVED");
        assertThat(receipt.path("reason").asString()).isEqualTo("AMOUNT_WITHIN_LIMIT");assertThat(receipt.path("policyVersion").asString()).isEqualTo("test-v1");
        assertThat(SERVICE.seen).hasSize(1);var wire=SERVICE.seen.getFirst();assertThat(wire.getAmount()).isEqualTo(amount);assertThat(wire.getRequestId()).matches("[a-f0-9]{64}");
        assertThat(wire.toString()).doesNotContain(a.subject(),b.subject(),a.account().toString(),b.account().toString(),a.token(),TOKENS.issuer());
        assertThat(call("GET","/api/v1/transfers/"+id+"/risk-decision",b.token(),null,null).statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/v1/transfers/"+id+"/risk-decision",operator(),null,null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/v1/transfers/"+id+"/risk-decision",null,null,null).statusCode()).isEqualTo(401);
        assertThat(balance(b)).isEqualTo(amount);assertThat(balance(a)).isEqualTo("0");
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.transfer_risk_decisions SET policy_version='changed' WHERE transfer_id=:id").param("id",UUID.fromString(id)).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var docs=json.readTree(call("GET","/v3/api-docs",null,null,null).body());
        assertThat(docs.path("paths").path("/api/v1/transfers").path("post").path("responses").has("503")).isTrue();
        assertThat(docs.path("paths").path("/api/v1/transfers").path("post").path("responses").path("503").path("content").has("application/problem+json")).isTrue();
        assertThat(docs.path("paths").path("/api/v1/transfers/{id}/risk-decision").path("get").path("operationId").asString()).isEqualTo("getMyTransferRiskDecision");
    }

    @Test void rejectedUnavailableAndInvalidResponsesFailClosedAndUnauthorizedCallsNeverReachRisk()throws Exception{
        var a=owner();var b=owner();cash(a,"100");String key=UUID.randomUUID().toString();
        assertThat(call("POST","/api/v1/transfers",b.token(),key,request(a,b,"1")).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/v1/transfers",operator(),key,request(a,b,"1")).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/v1/transfers",null,key,request(a,b,"1")).statusCode()).isEqualTo(401);assertThat(SERVICE.calls).hasValue(0);
        for(var mode:List.of(Mode.REJECT,Mode.UNAVAILABLE,Mode.WRONG_ID,Mode.UNKNOWN)){
            SERVICE.mode.set(mode);var result=transfer(a,b,key,"1");
            assertThat(result.statusCode()).as(result.body()).isEqualTo(mode==Mode.REJECT?422:503);
            assertThat(json.readTree(result.body()).path("code").asString()).isEqualTo(switch(mode){case REJECT->"RISK_REJECTED";case UNAVAILABLE->"RISK_UNAVAILABLE";default->"RISK_RESPONSE_INVALID";});
            if(mode!=Mode.REJECT)assertThat(result.headers().firstValue("Retry-After")).contains("1");
            assertThat(result.body()).doesNotContain("server-secret", "SELECT", a.subject());
            assertThat(transferCount(a)).isZero();assertThat(riskCount(a)).isZero();assertThat(balance(a)).isEqualTo("100");
        }
        SERVICE.mode.set(Mode.APPROVE);assertThat(transfer(a,b,key,"1").statusCode()).isEqualTo(201);
    }

    @Test void lateApprovalAfterDeadlineNeverPostsAndSameKeyRecovers()throws Exception{
        var a=owner();var b=owner();cash(a,"20");String key=UUID.randomUUID().toString();SERVICE.mode.set(Mode.LATE);
        var failed=transfer(a,b,key,"2");assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(json.readTree(failed.body()).path("code").asString()).isEqualTo("RISK_TIMEOUT");
        SERVICE.release.countDown();assertThat(SERVICE.lateDone.await(4,TimeUnit.SECONDS)).isTrue();
        assertThat(transferCount(a)).isZero();assertThat(riskCount(a)).isZero();assertThat(balance(a)).isEqualTo("20");
        SERVICE.mode.set(Mode.APPROVE);assertThat(transfer(a,b,key,"2").statusCode()).isEqualTo(201);
        assertThat(transferCount(a)).isEqualTo(1);assertThat(balance(a)).isEqualTo("18");
    }

    @Test void sameKeyConcurrentApprovalsPostOnceAndOutageReplayBypassesRpcAndConflictStays409()throws Exception{
        var a=owner();var b=owner();cash(a,"100");String key=UUID.randomUUID().toString();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<HttpResponse<String>>>();
            for(int n=0;n<6;n++)futures.add(pool.submit(()->{start.await();return transfer(a,b,key,"10");}));start.countDown();String expected=null;
            for(var f:futures){var r=f.get(15,TimeUnit.SECONDS);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);if(expected==null)expected=r.body();else assertThat(r.body()).isEqualTo(expected);}
            assertThat(transferCount(a)).isEqualTo(1);assertThat(riskCount(a)).isEqualTo(1);assertThat(balance(a)).isEqualTo("90");
            int calls=SERVICE.calls.get();SERVICE.mode.set(Mode.UNAVAILABLE);
            assertThat(transfer(a,b,key,"10").body()).isEqualTo(expected);assertThat(SERVICE.calls).hasValue(calls);
            assertThat(transfer(a,b,key,"11").statusCode()).isEqualTo(409);assertThat(SERVICE.calls).hasValue(calls);
        }
    }

    @Test void rpcHoldsNoDatabaseTransactionOrAccountLockAndFinalStateIsCheckedAgain()throws Exception{
        var a=owner();var b=owner();cash(a,"10");String key=UUID.randomUUID().toString();SERVICE.mode.set(Mode.PAUSE);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var attempt=pool.submit(()->transfer(a,b,key,"1"));assertThat(SERVICE.entered.await(5,TimeUnit.SECONDS)).isTrue();
            // Independent writer obtains the same account lock while the core waits on RPC.
            var freeze=call("POST","/api/v1/operations/accounts/"+a.account()+"/freeze",operator(),null,"{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}");
            assertThat(freeze.statusCode()).as(freeze.body()).isEqualTo(200);
            Long idleTransactions=jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'").query(Long.class).single();
            assertThat(idleTransactions).isZero();SERVICE.release.countDown();
            var r=attempt.get(5,TimeUnit.SECONDS);assertThat(r.statusCode()).isEqualTo(409);assertThat(transferCount(a)).isZero();assertThat(riskCount(a)).isZero();
        }finally{SERVICE.release.countDown();}
    }

    @Test void approvalExpiringWhileWaitingForLocksRollsBackPostingFeeUsageOutboxAndAudit()throws Exception{
        var a=owner();var b=owner();cash(a,"100");
        jdbc.sql("INSERT INTO banking.outgoing_policies(account_id,transaction_limit,daily_limit,fee,timezone) VALUES(:id,100,100,2,'UTC')").param("id",a.account()).update();
        long outbox=count("banking.outbox_events"),audit=count("banking.audit_events");
        var locked=new CountDownLatch(1);var unlock=new CountDownLatch(1);String key=UUID.randomUUID().toString();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(transactions).execute(s->{
                jdbc.sql("SELECT id FROM banking.accounts WHERE id=:id FOR UPDATE").param("id",a.account()).query(UUID.class).single();locked.countDown();
                try{if(!unlock.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Lock test timed out");}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var attempt=pool.submit(()->transfer(a,b,key,"10"));
            assertThat(SERVICE.entered.await(5,TimeUnit.SECONDS)).isTrue();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean waiting=false;
            while(System.nanoTime()<until){
                waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%banking.accounts%')").query(Boolean.class).single();
                if(waiting)break;Thread.sleep(25);
            }
            assertThat(waiting).as("Posting reached the final account lock after RPC").isTrue();
            Thread.sleep(650);unlock.countDown();holder.get(5,TimeUnit.SECONDS);
            var r=attempt.get(5,TimeUnit.SECONDS);assertThat(r.statusCode()).as(r.body()).isEqualTo(409);assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("RISK_APPROVAL_EXPIRED");
        }finally{unlock.countDown();}
        assertThat(transferCount(a)).isZero();assertThat(riskCount(a)).isZero();assertThat(balance(a)).isEqualTo("100");
        assertThat(count("banking.outbox_events")).isEqualTo(outbox);assertThat(count("banking.audit_events")).isEqualTo(audit);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.outgoing_charges WHERE account_id=:id").param("id",a.account()).query(Long.class).single()).isZero();
        assertThat(transfer(a,b,key,"10").statusCode()).isEqualTo(201);assertThat(balance(a)).isEqualTo("88");
    }

    @Test void riskReceiptInsertionFailureRollsBackMoneyAndSameKeyRemainsRetryable()throws Exception{
        var a=owner();var b=owner();cash(a,"10");long events=count("banking.outbox_events"),audit=count("banking.audit_events");
        jdbc.sql("CREATE FUNCTION banking.test_risk_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic risk store failure' USING ERRCODE='23514'; END $$").update();
        jdbc.sql("CREATE TRIGGER test_risk_failure BEFORE INSERT ON banking.transfer_risk_decisions FOR EACH ROW EXECUTE FUNCTION banking.test_risk_failure()").update();
        String key=UUID.randomUUID().toString();
        try{assertThat(transfer(a,b,key,"1").statusCode()).isEqualTo(500);}finally{jdbc.sql("DROP TRIGGER test_risk_failure ON banking.transfer_risk_decisions").update();jdbc.sql("DROP FUNCTION banking.test_risk_failure()").update();}
        assertThat(transferCount(a)).isZero();assertThat(balance(a)).isEqualTo("10");assertThat(count("banking.outbox_events")).isEqualTo(events);assertThat(count("banking.audit_events")).isEqualTo(audit);
        assertThat(transfer(a,b,key,"1").statusCode()).isEqualTo(201);
    }

    private long count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single();}
    private long transferCount(Owner a){return jdbc.sql("SELECT count(*) FROM banking.transfers WHERE source_account_id=:id").param("id",a.account()).query(Long.class).single();}
    private long riskCount(Owner a){return jdbc.sql("SELECT count(*) FROM banking.transfer_risk_decisions d JOIN banking.transfers t ON t.id=d.transfer_id WHERE t.source_account_id=:id").param("id",a.account()).query(Long.class).single();}
    private String balance(Owner a)throws Exception{return json.readTree(call("GET","/api/v1/ledger/accounts/"+a.account()+"/balance",a.token(),null,null).body()).path("postedBalance").asString();}
    private HttpResponse<String> transfer(Owner a,Owner b,String key,String amount)throws Exception{return call("POST","/api/v1/transfers",a.token(),key,request(a,b,amount));}
    private String request(Owner a,Owner b,String amount){return "{\"sourceAccountId\":\""+a.account()+"\",\"destinationAccountId\":\""+b.account()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}";}
    private String operator()throws Exception{return TOKENS.token(b->b.subject("risk-operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));}
    private void cash(Owner a,String amount)throws Exception{var r=call("POST","/api/v1/operations/cash-ins",operator(),UUID.randomUUID().toString(),"{\"accountId\":\""+a.account()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}");assertThat(r.statusCode()).as(r.body()).isEqualTo(201);}
    private HttpResponse<String> call(String method,String path,String token,String key,String body)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");
        return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Owner owner()throws Exception{
        UUID customer=UUID.randomUUID(),account=UUID.randomUUID(),ledger=UUID.randomUUID();new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic Risk',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",account).param("customer",customer).update();
            jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic Risk','LIABILITY','CREDIT','VND',:bank,now())")
                    .param("id",ledger).param("code",ledger.toString()).param("bank",account).update();jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",account).update();return null;});
        return new Owner(account,customer.toString(),TOKENS.token(b->b.subject(customer.toString())));
    }
    private static final class RiskServer extends RiskEvaluatorGrpc.RiskEvaluatorImplBase {
        final AtomicReference<Mode> mode=new AtomicReference<>(Mode.APPROVE);final AtomicInteger calls=new AtomicInteger();
        final List<AssessTransferRequest> seen=new CopyOnWriteArrayList<>();volatile CountDownLatch entered,release,lateDone;
        @Override public void assessTransfer(AssessTransferRequest r,StreamObserver<AssessTransferResponse> reply){
            calls.incrementAndGet();seen.add(r);var current=mode.get();entered.countDown();
            if(current==Mode.UNAVAILABLE){reply.onError(Status.UNAVAILABLE.withDescription("server-secret SELECT synthetic").asRuntimeException());return;}
            if(current==Mode.PAUSE || current==Mode.LATE){try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            try{reply.onNext(AssessTransferResponse.newBuilder().setRequestId(current==Mode.WRONG_ID?"b".repeat(64):r.getRequestId()).setPolicyVersion("test-v1")
                    .setOutcome(current==Mode.REJECT?Outcome.REJECT:current==Mode.UNKNOWN?Outcome.OUTCOME_UNSPECIFIED:Outcome.APPROVE)
                    .setReason(current==Mode.REJECT?Reason.AMOUNT_LIMIT_EXCEEDED:Reason.AMOUNT_WITHIN_LIMIT).build());reply.onCompleted();}
            finally{if(current==Mode.LATE)lateDone.countDown();}
        }
    }
}
