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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PerformanceBaselineIT {
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
        r.add("server.address", () -> "127.0.0.1");
        r.add("bank.risk.enabled", () -> "false");
        r.add("bank.kafka.enabled", () -> "false");
        r.add("bank.outbox.relay.enabled", () -> "false");
        r.add("bank.monitoring.trace-sampling", () -> "0.1");
        r.add("bank.monitoring.scheduled", () -> "false");
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private record Owner(String subject, String token, UUID id) { }

    @Test void syntheticHttpPostgresBaselineAndFinancialClosure() throws Exception {
        boolean full=Boolean.getBoolean("bank.benchmark.full");
        String revision=System.getProperty("bank.benchmark.revision","working-tree");
        if(!revision.matches("[a-f0-9]{7,40}|working-tree"))throw new IllegalArgumentException("Invalid benchmark revision");
        var a=owner();var b=owner();fund(a,"1000000");
        int read=full?400:8,write=full?150:8,readWarmup=full?50:2,writeWarmup=full?20:2;
        int[] levels=full?new int[]{1,4,16}:new int[]{1,4};
        var scenarios=new ArrayList<Scenario>();
        for(int concurrency:levels) {
            for(int n=0;n<readWarmup;n++)assertThat(http.send(readRequest(a),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            scenarios.add(measure("balance-read",concurrency,read,readWarmup,()->readRequest(a),200,"postedBalance"));
            for(int n=0;n<writeWarmup;n++)assertThat(http.send(writeRequest(a,b),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
            scenarios.add(measure("contended-transfer-create",concurrency,write,writeWarmup,()->writeRequest(a,b),201,"journalId"));
        }
        long posted=(long)levels.length*(write+writeWarmup);
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion",1);report.put("profile",full?"full-local-v1":"ci-correctness-smoke-v1");
        report.put("measuredAt",java.time.Instant.now().toString());report.put("codeRevision",revision);
        report.put("javaVersion",System.getProperty("java.version"));report.put("springBootVersion",org.springframework.boot.SpringBootVersion.getVersion());
        report.put("postgresVersion",jdbc.sql("SHOW server_version").query(String.class).single());
        report.put("operatingSystem",System.getProperty("os.name"));report.put("architecture",System.getProperty("os.arch"));
        report.put("availableProcessors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("transport","loopback HTTP/1.1, one reused HttpClient, synthetic JWT/JWKS, disposable PostgreSQL16.14");
        report.put("loadModel","closed loop, bounded worker concurrency, disjoint read/write stages; all writes contend on one funded account pair");
        report.put("latencyDefinition","client send to fully consumed body; includes transport errors/timeouts, excludes request construction; exact empirical nearest rank");
        report.put("throughputDefinition","all measured attempts / barrier release to all workers completed; not offered load or successful-payment capacity");
        report.put("sampling",0.1);report.put("risk","disabled/bypassed");report.put("fee","0 VND");report.put("brokerRelay","disabled");
        report.put("timingAssertions",false);report.put("warmupIncluded",false);report.put("scenarios",scenarios);
        report.put("financialClosure",Map.of("uniqueTransfers",posted,"sourcePostedBalance",balance(a).toPlainString(),"destinationPostedBalance",balance(b).toPlainString()));
        if(full) {
            var output=java.nio.file.Path.of(".tools","BL-020-benchmark.json");java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertThat(scenarios).allSatisfy(s->{assertThat(s.httpErrors()+s.timeouts()+s.transportErrors()+s.invalidBodies()).as(s.name()+" concurrency="+s.concurrency()).isZero();});
        assertThat(balance(a)).isEqualByComparingTo(new BigDecimal(1000000-posted));assertThat(balance(b)).isEqualByComparingTo(new BigDecimal(posted));
        assertThat(jdbc.sql("SELECT count(*) FROM banking.transfers WHERE source_account_id=:id").param("id",a.id()).query(Long.class).single()).isEqualTo(posted);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.outbox_events").query(Long.class).single()).isEqualTo(posted+1);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.audit_events WHERE action='LEDGER_JOURNAL_POSTED'").query(Long.class).single()).isEqualTo(posted+1);
    }
    public record Scenario(String name,int concurrency,int warmup,int successful,long httpErrors,long timeouts,long transportErrors,long invalidBodies,
                           vn.banklab.core.support.BenchmarkSummary.Distribution distribution) { }
    private Scenario measure(String name,int concurrency,int count,int warmup,java.util.concurrent.Callable<HttpRequest> request,
                             int expected,String requiredField)throws Exception {
        var ready=new CountDownLatch(concurrency);var start=new CountDownLatch(1);long[] latencies=new long[count];
        var httpErrors=new LongAdder();var timeouts=new LongAdder();var transportErrors=new LongAdder();var invalidBodies=new LongAdder();var successful=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(concurrency)) {
            var futures=new ArrayList<Future<?>>();
            for(int worker=0;worker<concurrency;worker++) {
                final int slot=worker;
                futures.add(workers.submit(()->{
                    ready.countDown();start.await();
                    for(int index=slot;index<count;index+=concurrency) {
                        var call=request.call();long began=System.nanoTime();HttpResponse<String> response=null;
                        try {response=http.send(call,HttpResponse.BodyHandlers.ofString());}
                        catch(HttpTimeoutException timeout){timeouts.increment();}
                        catch(java.io.IOException io){transportErrors.increment();}
                        finally{latencies[index]=System.nanoTime()-began;}
                        if(response!=null) {
                            if(response.statusCode()!=expected)httpErrors.increment();
                            else {try{if(tree(response).has(requiredField))successful.incrementAndGet();else invalidBodies.increment();}catch(RuntimeException invalid){invalidBodies.increment();}}
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();long began=System.nanoTime();start.countDown();
            for(var future:futures)future.get(120,TimeUnit.SECONDS);
            long elapsed=System.nanoTime()-began;
            return new Scenario(name,concurrency,warmup,successful.get(),httpErrors.sum(),timeouts.sum(),transportErrors.sum(),invalidBodies.sum(),
                    vn.banklab.core.support.BenchmarkSummary.summarize(latencies,elapsed));
        }
    }
    private HttpRequest readRequest(Owner a) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/ledger/accounts/"+a.id()+"/balance"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+a.token()).GET().build();
    }
    private HttpRequest writeRequest(Owner a,Owner b) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transfers"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+a.token()).header("Idempotency-Key",key()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(transferBody(a,b,"1"))).build();
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
    private static String transferBody(Owner a, Owner b, String amount) { return "{\"sourceAccountId\":\"" + a.id() + "\",\"destinationAccountId\":\"" + b.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}"; }
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
}
