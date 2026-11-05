package vn.banklab.core.observability;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.support.SignedTokenServer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@org.springframework.context.annotation.Import(MonitoringIT.Probe.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringIT {
    static final SignedTokenServer TOKENS;
    static {
        try { TOKENS = new SignedTokenServer(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri", TOKENS::issuer);
        r.add("bank.iam.jwk-set-uri", TOKENS::jwks);
        r.add("bank.iam.audience", () -> "bank-api");
        r.add("bank.monitoring.trace-sampling", () -> "1");
        r.add("bank.monitoring.statement-timeout-millis", () -> "1500");
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired vn.banklab.core.observability.application.RecentTraces recent;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ROUTE = "/api/v1/operations/monitoring";
    private record Owner(String subject, String token, UUID id) { }

    @Test void protectsScrapeTraceAndSnapshotButKeepsHealthMinimal() throws Exception {
        var a=owner();var op=operator();
        for(String token:Arrays.asList(null,a.token())) {
            int expected=token==null?401:403;
            for(String path:List.of("/actuator/prometheus",ROUTE+"/traces",ROUTE+"/snapshot"))
                assertThat(call("GET",path,token,null,null).statusCode()).isEqualTo(expected);
            assertThat(call("POST",ROUTE+"/snapshot/refresh",token,null,null).statusCode()).isEqualTo(expected);
        }
        for(String path:List.of("/actuator/env","/actuator/configprops","/actuator/heapdump","/actuator/metrics","/actuator/beans"))
            assertThat(call("GET",path,op,null,null).statusCode()).isEqualTo(403);
        var health=call("GET","/actuator/health",null,null,null);assertThat(health.statusCode()).isEqualTo(200);
        assertThat(tree(health).propertyNames()).contains("status").containsOnly("status","groups");
        assertThat(health.body()).doesNotContain("components","jdbc:","password");
        var metrics=call("GET","/actuator/prometheus",op,null,null);assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(metrics.body()).contains("bank_http_server_duration_seconds_bucket","bank_runtime_heap_used");
        assertThat(call("GET",ROUTE+"/traces?limit=101",op,null,null).statusCode()).isEqualTo(400);
        assertThat(call("GET",ROUTE+"/traces?limit=0",op,null,null).statusCode()).isEqualTo(400);
    }
    @Test void sensitiveInputsNeverBecomeMetricLabelsOrSpanFieldsAndParentIsIgnored() throws Exception {
        var a=owner();var op=operator();String secret="PRIVATE_SECRET_020";
        String callerTrace="11111111111111111111111111111111";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/accounts/"+a.id()+"?secret="+secret))
                .header("Authorization","Bearer "+a.token()).header("X-Correlation-ID",secret).header("X-Trace-ID",secret)
                .header("Idempotency-Key",secret).header("baggage","owner="+secret)
                .header("traceparent","00-"+callerTrace+"-1111111111111111-01").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        String trace=response.headers().firstValue("X-Trace-ID").orElseThrow();assertThat(trace).matches("[a-f0-9]{32}").isNotEqualTo(callerTrace);
        var span=awaitTrace(trace);assertThat(span.route()).isEqualTo("/api/v1/accounts/{id}");assertThat(span.status()).isEqualTo(200);
        for(int i=0;i<12;i++) call("GET","/api/v1/accounts/"+UUID.randomUUID()+"?private="+secret,a.token(),null,null);
        for(int i=0;i<12;i++) call("GET","/api/v1/private-"+UUID.randomUUID()+"?private="+secret,a.token(),null,null);
        call("POST","/api/v1/accounts",a.token(),null,"{\"currency\":\"VND\",\"private\":\""+secret+"\"}");
        var scrape=call("GET","/actuator/prometheus",op,null,null).body();
        var traces=call("GET",ROUTE+"/traces?limit=100",op,null,null).body();
        assertThat(scrape).doesNotContain(secret,a.id().toString(),a.subject(),a.token(),"http_url","uri=","correlation",callerTrace);
        assertThat(traces).doesNotContain(secret,a.id().toString(),a.subject(),a.token(),"baggage","exception",callerTrace);
        var routes=meters.find("bank.http.server.duration").timers().stream().map(t->t.getId().getTag("route")).distinct().toList();
        assertThat(routes).contains("UNMAPPED","/api/v1/accounts/{id}");
        assertThat(routes).noneMatch(r->r.contains("private-") || r.contains("?"));
        assertThat(meters.getMeters()).allSatisfy(m->assertThat(m.getId().getName()).startsWith("bank."));
    }
    @Test void serverFailureHasFixedSafeOutcomeAndNoExceptionPayload() throws Exception {
        var r=call("GET","/api/v1/monitoring-probe/error/"+UUID.randomUUID(),operator(),null,null);
        assertThat(r.statusCode()).isEqualTo(500);var trace=awaitTrace(r.headers().firstValue("X-Trace-ID").orElseThrow());
        assertThat(trace.route()).isEqualTo("/api/v1/monitoring-probe/error/{id}");assertThat(trace.outcome()).isEqualTo("SERVER_ERROR");
        assertThat(json.writeValueAsString(trace)).doesNotContain("PRIVATE_EXCEPTION_020");
        assertThat(meters.find("bank.http.server.duration").tags("route",trace.route(),"status","5xx").timer().count()).isEqualTo(1);
    }
    @Test void asyncRequestFinishesOnceAtCompletionInsteadOfInitialDispatch() throws Exception {
        Probe.started=new CountDownLatch(1);Probe.release=new CountDownLatch(1);
        double before=durationCount("/api/v1/monitoring-probe/async");
        var future=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/monitoring-probe/async"))
                .header("Authorization","Bearer "+operator()).GET().build(),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(Probe.started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(future.isDone()).isFalse();
            assertThat(durationCount("/api/v1/monitoring-probe/async")).isEqualTo(before);
            assertThat(meters.get("bank.http.server.active").gauge().value()).isGreaterThanOrEqualTo(1);
        } finally {Probe.release.countDown();}
        var result=future.get(5,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);
        var trace=awaitTrace(result.headers().firstValue("X-Trace-ID").orElseThrow());assertThat(trace.route()).isEqualTo("/api/v1/monitoring-probe/async");
        assertThat(durationCount(trace.route())).isEqualTo(before+1);
        assertThat(recent.latest(100).stream().filter(t->t.traceId().equals(trace.traceId())).count()).isEqualTo(1);
    }
    @Test void snapshotReadIsAggregateOnlyAndDoesNotWriteFinancialData() throws Exception {
        var a=owner();var b=owner();fund(a,"100");var op=operator();
        var pending=call("POST","/api/v1/operations/requests",op,key(),"{\"type\":\"FREEZE\",\"targetId\":\""+a.id()+"\",\"expectedAccountVersion\":0,\"reason\":\"SECURITY_REVIEW\"}");assertThat(pending.statusCode()).isEqualTo(201);
        seedExternalUnknown(a,b);seedNotificationFailure(false);seedNotificationFailure(true);
        var before=inventory();var r=call("POST",ROUTE+"/snapshot/refresh",op,null,null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var s=tree(r);assertThat(s.path("available").asBoolean()).isTrue();assertThat(s.path("stale").asBoolean()).isFalse();
        var c=s.path("counts");
        assertThat(c.path("outboxPending").asLong()).isEqualTo(number("SELECT count(*) FROM banking.outbox_deliveries WHERE state='PENDING'"));
        assertThat(c.path("externalUnknown").asLong()).isEqualTo(number("SELECT count(*) FROM banking.external_payments WHERE state='UNKNOWN'"));
        assertThat(c.path("externalPrepared").asLong()).isEqualTo(number("SELECT count(*) FROM banking.external_payments WHERE state='PREPARED'"));
        assertThat(c.path("operationsPending").asLong()).isEqualTo(number("SELECT count(*) FROM banking.operation_requests WHERE status='PENDING'"));
        assertThat(c.path("notificationRetries").asLong()).isEqualTo(number("SELECT count(*) FROM banking.notification_failures WHERE NOT quarantined"));
        assertThat(c.path("notificationQuarantined").asLong()).isEqualTo(number("SELECT count(*) FROM banking.notification_failures WHERE quarantined"));
        assertThat(inventory()).isEqualTo(before);assertThat(balance(a)).isEqualByComparingTo("100");
        assertThat(r.body()).doesNotContain(a.id().toString(),b.id().toString(),a.subject(),"sql","jdbc:","issuer");
        var scrape=call("GET","/actuator/prometheus",op,null,null).body();assertThat(scrape).contains("bank_monitoring_available 1.0","bank_backlog_external_unknown");
    }
    @Test void timeoutPreservesLastSuccessfulCountsAndScrapeDoesNotQueryLockedTables() throws Exception {
        var op=operator();var baseline=tree(call("POST",ROUTE+"/snapshot/refresh",op,null,null));
        assertThat(baseline.path("available").asBoolean()).isTrue();
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=c.createStatement()) {
            c.setAutoCommit(false);sql.execute("LOCK TABLE banking.outbox_deliveries IN ACCESS EXCLUSIVE MODE");
            var scrape=call("GET","/actuator/prometheus",op,null,null);assertThat(scrape.statusCode()).isEqualTo(200);
            var r=call("POST",ROUTE+"/snapshot/refresh",op,null,null);assertThat(r.statusCode()).isEqualTo(200);var failed=tree(r);
            assertThat(failed.path("available").asBoolean()).isFalse();assertThat(failed.path("stale").asBoolean()).isTrue();
            assertThat(failed.path("problem").asString()).isEqualTo("MONITORING_SNAPSHOT_UNAVAILABLE");
            assertThat(failed.path("lastSuccessAt")).isEqualTo(baseline.path("lastSuccessAt"));assertThat(failed.path("counts")).isEqualTo(baseline.path("counts"));
            assertThat(r.body()).doesNotContain("statement_timeout","PSQL","SQLException");c.rollback();
        }
        assertThat(tree(call("POST",ROUTE+"/snapshot/refresh",op,null,null)).path("available").asBoolean()).isTrue();
    }
    @Test void concurrentRefreshIsRejectedInsteadOfStartingAnotherDatabaseRead() throws Exception {
        var op=operator();
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=c.createStatement();var pool=Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);sql.execute("LOCK TABLE banking.outbox_deliveries IN ACCESS EXCLUSIVE MODE");
            var first=pool.submit(()->call("POST",ROUTE+"/snapshot/refresh",op,null,null));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%outbox_pending%')").query(Boolean.class).single();
                if(waiting)break;Thread.sleep(10);
            }
            assertThat(waiting).isTrue();problem(call("POST",ROUTE+"/snapshot/refresh",op,null,null),409,"MONITORING_REFRESH_BUSY");
            c.commit();assertThat(tree(first.get(5,TimeUnit.SECONDS)).path("available").asBoolean()).isTrue();
        }
    }
    @Test void swaggerDescribesReadOnlyMonitoringAndNoStore() throws Exception {
        var op=operator();var r=call("GET",ROUTE+"/snapshot",op,null,null);assertThat(r.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var docs=tree(call("GET","/v3/api-docs",null,null,null));assertThat(docs.path("paths").has(ROUTE+"/snapshot/refresh")).isTrue();
    }
    private double durationCount(String route){return meters.find("bank.http.server.duration").tag("route",route).timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum();}
    private vn.banklab.core.observability.application.RecentTraces.Trace awaitTrace(String trace)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){var t=recent.latest(100).stream().filter(s->s.traceId().equals(trace)).findFirst();if(t.isPresent())return t.get();Thread.sleep(10);}
        throw new AssertionError("Completed trace not found");
    }
    private long number(String query){return jdbc.sql(query).query(Long.class).single();}
    private String inventory(){return jdbc.sql("SELECT jsonb_build_object('journals',(SELECT jsonb_agg(to_jsonb(j) ORDER BY id) FROM banking.ledger_journals j),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY event_id) FROM banking.outbox_events e),'audit',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM banking.audit_events a),'operations',(SELECT jsonb_agg(to_jsonb(o) ORDER BY id) FROM banking.operation_requests o),'external',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM banking.external_payments e))::text").query(String.class).single();}
    private void seedNotificationFailure(boolean quarantined){jdbc.sql("INSERT INTO banking.notification_failures(source_topic,source_partition,source_offset,payload_hash,attempts,available_at,quarantined,error_code) VALUES(:topic,0,0,:hash,1,clock_timestamp(),:quarantined,'PROCESSING_FAILED')").param("topic",key()).param("hash","a".repeat(64)).param("quarantined",quarantined).update();}
    private void seedExternalUnknown(Owner a,Owner b)throws Exception {
        UUID id=UUID.randomUUID();try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=c.createStatement()) {
            c.setAutoCommit(false);sql.execute("INSERT INTO banking.external_payments(id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,settlement_account_id,beneficiary,amount,reserved_amount,partner_instance,risk_outcome,risk_policy,risk_reason,risk_assessed_at,state) VALUES('"+id+"','"+TOKENS.issuer()+"','"+a.subject()+"','"+id+"','"+"a".repeat(64)+"','"+a.id()+"','"+b.id()+"','SIM_METRICS',10,10,'"+UUID.randomUUID()+"','BYPASSED','disabled-v1','RISK_DISABLED',clock_timestamp(),'CREATING')");
            sql.execute("UPDATE banking.external_payments SET state='PREPARED',version=1,updated_at=clock_timestamp() WHERE id='"+id+"'");
            sql.execute("UPDATE banking.external_payments SET state='UNKNOWN',version=2,updated_at=clock_timestamp() WHERE id='"+id+"'");c.commit();
        }
    }
    private static String operator()throws Exception{return token(key(),"OPERATOR");}
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.web.bind.annotation.RestController
    @vn.banklab.core.shared.web.ApiErrorResponses
    static class Probe {
        static volatile CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/monitoring-probe/error/{id}")
        String error(){throw new IllegalStateException("PRIVATE_EXCEPTION_020");}
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/monitoring-probe/async")
        org.springframework.web.context.request.async.DeferredResult<String> async(){
            var result=new org.springframework.web.context.request.async.DeferredResult<String>(10000L);
            CompletableFuture.runAsync(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);result.setResult("done");}catch(InterruptedException e){Thread.currentThread().interrupt();result.setErrorResult(e);}});return result;
        }
    }
    private Owner owner() throws Exception {
        var subject = key(); var token = token(subject, "CUSTOMER");
        var customer = call("POST", "/api/v1/customers", token, null, "{\"displayName\":\"Reversal Demo\"}"); assertThat(customer.statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/v1/operations/customers/" + tree(customer).path("id").asString() + "/kyc-decisions", token(key(), "OPERATOR"), null,
                "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}").statusCode()).isEqualTo(200);
        var account = call("POST", "/api/v1/accounts", token, null, "{\"currency\":\"VND\"}"); assertThat(account.statusCode()).isEqualTo(201);
        var owner = new Owner(subject, token, UUID.fromString(tree(account).path("id").asString()));
        assertThat(call("POST", "/api/v1/ledger/accounts/" + owner.id() + "/provision", token, null, null).statusCode()).isEqualTo(200);
        return owner;
    }
    private HttpResponse<String> cashIn(Owner a, String amount, String op, String key) throws Exception { return call("POST", "/api/v1/operations/cash-ins", op, key, "{\"accountId\":\"" + a.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}"); }
    private HttpResponse<String> fund(Owner a, String amount) throws Exception { var r = cashIn(a, amount, token(key(), "OPERATOR"), key()); assertThat(r.statusCode()).as(r.body()).isEqualTo(201); return r; }
    private BigDecimal balance(Owner a) { return jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", a.id()).query(BigDecimal.class).single(); }
    private String lines(UUID journal) { return jdbc.sql("SELECT string_agg(ledger_account_id::text || ':' || side || ':' || amount::text,',' ORDER BY id) FROM banking.ledger_postings WHERE journal_id=:id").param("id", journal).query(String.class).single(); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String token(String subject, String... roles) throws Exception { return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles))))); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private HttpResponse<String> call(String method, String path, String token, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(path.startsWith("/v3/api-docs") ? 60 : 40)).header("X-Correlation-ID", "reversal-010");
        if (token != null) builder.header("Authorization", "Bearer " + token); if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> r, int status, String code) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); assertThat(tree(r).path("code").asString()).isEqualTo(code); assertThat(tree(r).path("correlationId").asString()).isEqualTo("reversal-010"); }
}
