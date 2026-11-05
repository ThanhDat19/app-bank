package vn.banklab.core.realtime;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.notification.application.NotificationService;
import vn.banklab.core.notification.application.SourceRecord;
import vn.banklab.core.outbox.application.OutboxEvent;
import vn.banklab.core.realtime.application.AccountEventStreams;
import vn.banklab.core.support.SignedTokenServer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountEventsIT {
    static final SignedTokenServer TOKENS;
    static { try { TOKENS = new SignedTokenServer(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri", TOKENS::issuer); r.add("bank.iam.jwk-set-uri", TOKENS::jwks);
        r.add("bank.iam.audience", () -> "bank-api");
        r.add("bank.sse.poll-millis", () -> 100); r.add("bank.sse.lifetime-millis", () -> 6000);
        r.add("bank.sse.max-connections", () -> 3); r.add("bank.sse.max-per-actor", () -> 1);
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationService notifications;
    @Autowired AccountEventStreams streams;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private record Owner(UUID account, UUID ledger, String subject, String token) { }
    private String account(Owner o) { return "/api/v1/accounts/" + o.account(); }
    private String events(Owner o) { return account(o) + "/events"; }

    @AfterEach void allDisconnectedWorkersReleaseTheirAdmission() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(true) {
            int active;
            synchronized(streams) { active=(Integer)ReflectionTestUtils.getField(streams,"connections"); }
            if(active==0)return;
            if(System.nanoTime()>until) { assertThat(active).as("Disconnected SSE workers must release admission").isZero(); }
            Thread.sleep(25);
        }
    }

    @Test void handshakeRequiresRealJwtCustomerAndOwnedAccountBeforeStreaming() throws Exception {
        var a = owner(false); var b = owner(false);
        assertThat(call("GET", events(a), null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", events(a) + "?access_token=" + a.token(), null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", events(a), TOKENS.wrongSignature(), null).statusCode()).isEqualTo(401);
        assertThat(call("GET", events(a), operator(), null).statusCode()).isEqualTo(403);
        var denied = call("GET", events(a), b.token(), null);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(call("GET", "/api/v1/accounts/not-a-uuid/events", a.token(), null).statusCode()).isEqualTo(400);
        String expiredWithinClockSkew = TOKENS.token(c -> c.subject(a.subject()).expirationTime(Date.from(Instant.now().minusSeconds(1))));
        assertThat(call("GET", events(a), expiredWithinClockSkew, null).statusCode()).isEqualTo(401);
        try (var stream = open(a, null)) { assertResync(stream.nextEvent(), a, "CONNECTED"); }
    }

    @Test void wireContractHasSanitizedResyncHeartbeatAndSwagger() throws Exception {
        var a = owner(false);
        try (var stream = open(a, null)) {
            assertThat(stream.response.statusCode()).isEqualTo(200);
            assertThat(stream.response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            assertThat(stream.response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
            assertThat(stream.response.headers().firstValue("X-Accel-Buffering").orElseThrow()).isEqualTo("no");
            assertThat(stream.response.headers().firstValue("X-Correlation-ID").orElseThrow()).isEqualTo("sse-contract-123");
            var frame = stream.nextEvent(); assertResync(frame, a, "CONNECTED");
            assertThat(frame).contains("retry:5000").doesNotContain("id:", a.subject(), a.token(), "balance", "journal");
            assertThat(stream.nextFrame()).isEqualTo(":keepalive\n");
            // The streaming worker must never make an unrelated anonymous HTTP request authenticated.
            assertThat(call("GET", account(a), null, null).statusCode()).isEqualTo(401);
        }
        var docs = json.readTree(call("GET", "/v3/api-docs", null, null).body());
        var operation = docs.path("paths").path("/api/v1/accounts/{accountId}/events").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("streamOwnedAccountHints");
        assertThat(operation.path("responses").path("200").path("content").has("text/event-stream")).isTrue();
        assertThat(operation.path("responses").has("409")).isTrue();
    }

    @Test void committedMoneyTriggersResyncAndReconnectUsesRestAndOriginalIdempotencyKey() throws Exception {
        var a = owner(true); var b = owner(true);
        try (var stream = open(a, null)) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            assertThat(cashIn(a, "100").statusCode()).isEqualTo(201);
            assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            assertThat(balance(a).path("postedBalance").asString()).isEqualTo("100");
        }
        String key = UUID.randomUUID().toString();
        String request = "{\"sourceAccountId\":\"" + a.account() + "\",\"destinationAccountId\":\"" + b.account() + "\",\"amount\":\"30\",\"currency\":\"VND\"}";
        var original = withKey("/api/v1/transfers", a.token(), key, request);
        assertThat(original.statusCode()).as(original.body()).isEqualTo(201);
        var receipt = json.readTree(original.body());
        long journals = countJournals(a);
        try (var stream = reopen(a, "untrusted-and-stale:123")) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            assertThat(balance(a).path("postedBalance").asString()).isEqualTo("70");
            assertThat(call("GET", "/api/v1/transfers/" + receipt.path("id").asString(), a.token(), null).body()).isEqualTo(original.body());
            var replay = withKey("/api/v1/transfers", a.token(), key, request);
            assertThat(replay.statusCode()).isEqualTo(201); assertThat(replay.body()).isEqualTo(original.body());
            assertThat(countJournals(a)).isEqualTo(journals);
            assertThat(stream.nextFrame()).isEqualTo(":keepalive\n");
        }
    }

    @Test void uncommittedAndRolledBackPostingsAreInvisibleUntilCommit() throws Exception {
        var a = owner(true); var ready = new CountDownLatch(1); var commit = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var stream = open(a, null)) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            var writer = executor.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                post(a, Instant.parse("2020-01-01T00:00:00Z")); ready.countDown();
                try { if (!commit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test commit latch timed out"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                long sampleUntil=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400);
                do { assertThat(stream.nextFrame()).isEqualTo(":keepalive\n"); } while(System.nanoTime()<sampleUntil);
                commit.countDown(); writer.get(5, TimeUnit.SECONDS);
                assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            } finally { commit.countDown(); }
            new TransactionTemplate(transactions).execute(s -> { post(a, Instant.now()); s.setRollbackOnly(); return null; });
            assertThat(stream.nextFrame()).isEqualTo(":keepalive\n");
            assertThat(countJournals(a)).isEqualTo(1);
        }
    }

    @Test void lateNotificationOutsideLatestListStillTriggersHint() throws Exception {
        var a = owner(true);
        new TransactionTemplate(transactions).execute(s -> { post(a, Instant.parse("2020-01-01T00:00:00Z")); return null; });
        // Newer notification already visible; delivery of the old event must not depend on max occurredAt.
        new TransactionTemplate(transactions).execute(s -> { post(a, Instant.now().minusSeconds(1)); return null; });
        var sourceRows = jdbc.sql("SELECT e.* FROM banking.outbox_events e JOIN banking.ledger_journals j ON j.id=e.aggregate_id JOIN banking.ledger_postings p ON p.journal_id=j.id WHERE p.ledger_account_id=:ledger ORDER BY j.posted_at DESC")
                .param("ledger", a.ledger()).query((r,n) -> {
                    var event=new OutboxEvent(r.getObject("event_id",UUID.class),r.getObject("aggregate_id",UUID.class),r.getString("aggregate_type"),r.getLong("aggregate_version"),r.getString("event_type"),r.getInt("schema_version"),r.getTimestamp("occurred_at").toInstant(),r.getString("payload"));
                    return new SourceRecord("sse-test-"+a.account(),0,n,event.aggregateId().toString(),json.writeValueAsString(event));
                }).list();
        notifications.accept(sourceRows.get(0));
        try (var stream = open(a, null)) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            notifications.accept(sourceRows.get(1));
            assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            assertThat(json.readTree(call("GET", account(a) + "/notifications?limit=1", a.token(), null).body()).get(0).path("eventId").asString()).isEqualTo(json.readTree(sourceRows.get(0).value()).path("eventId").asString());
            notifications.accept(sourceRows.get(1)); // Consumer replay must not create another hint.
            assertThat(stream.nextFrame()).isEqualTo(":keepalive\n");
        }
    }

    @Test void holdExpiryAndLifecycleChangesAreHintsWithoutFinancialWritesBySse() throws Exception {
        var a = owner(true); var b = owner(true); assertThat(cashIn(a, "100").statusCode()).isEqualTo(201);
        try (var stream = open(a, null)) {
            assertResync(stream.nextEvent(), a, "CONNECTED"); long journals = countJournals(a);
            String expiry = Instant.now().plusMillis(2000).toString();
            var hold = withKey("/api/v1/holds", a.token(), UUID.randomUUID().toString(), "{\"accountId\":\"" + a.account() + "\",\"destinationAccountId\":\"" + b.account() + "\",\"amount\":\"20\",\"expiresAt\":\"" + expiry + "\"}");
            assertThat(hold.statusCode()).as(hold.body()).isEqualTo(201);
            assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            assertThat(balance(a).path("reservedBalance").asString()).isEqualTo("20");
            assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            assertThat(balance(a).path("reservedBalance").asString()).isEqualTo("0");
            var freeze = call("POST", "/api/v1/operations/accounts/" + a.account() + "/freeze", operator(), "{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}");
            assertThat(freeze.statusCode()).as(freeze.body()).isEqualTo(200);
            assertResync(stream.nextEvent(), a, "STATE_CHANGED");
            assertThat(json.readTree(call("GET", account(a), a.token(), null).body()).path("status").asString()).isEqualTo("FROZEN");
            assertThat(countJournals(a)).isEqualTo(journals);
        }
        var closed = owner(false);
        assertThat(call("POST", account(closed) + "/close", closed.token(), "{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}").statusCode()).isEqualTo(200);
        try (var stream = open(closed, null)) { assertResync(stream.nextEvent(), closed, "CONNECTED"); }
    }

    @Test void actorAndGlobalCapacityAreBoundedAndDisconnectReleasesSlots() throws Exception {
        var a = owner(false); var b = owner(false); var c = owner(false); var d = owner(false);
        try (var first = open(a, null); var second = open(b, null); var third = open(c, null)) {
            assertResync(first.nextEvent(), a, "CONNECTED"); assertResync(second.nextEvent(), b, "CONNECTED"); assertResync(third.nextEvent(), c, "CONNECTED");
            var actorLimit = call("GET", events(a), a.token(), null);
            assertThat(actorLimit.statusCode()).isEqualTo(409);
            assertThat(json.readTree(actorLimit.body()).path("code").asString()).isEqualTo("SSE_CONNECTION_LIMIT");
            assertThat(call("GET", events(d), d.token(), null).statusCode()).isEqualTo(409);
            assertThat(call("GET", events(a), d.token(), null).statusCode()).isEqualTo(404);
            first.close();
            try (var recovered = reopen(d, null)) { assertResync(recovered.nextEvent(), d, "CONNECTED"); }
        }
    }

    @Test void repeatedDisconnectsDoNotInterruptReusedWorkersForOtherOwners() throws Exception {
        var a=owner(false); var b=owner(false);
        for(int round=0;round<6;round++) {
            try(var first=reopen(a,null);var second=reopen(b,null)) {
                assertResync(first.nextEvent(),a,"CONNECTED"); assertResync(second.nextEvent(),b,"CONNECTED");
                assertThat(first.nextFrame()).isEqualTo(":keepalive\n");
                first.close();
                try(var replaced=reopen(a,"previous-session")) {
                    assertResync(replaced.nextEvent(),a,"CONNECTED");
                    assertThat(second.nextFrame()).isEqualTo(":keepalive\n");
                    assertThat(replaced.nextFrame()).isEqualTo(":keepalive\n");
                }
            }
        }
    }

    @Test void expirationAndLifetimeTerminateStreamAndAllowFreshAuthentication() throws Exception {
        var a = owner(false);
        Instant expiry = Instant.now().plusSeconds(2);
        String shortToken = TOKENS.token(c -> c.subject(a.subject()).expirationTime(Date.from(expiry)));
        var shortOwner = new Owner(a.account(), null, a.subject(), shortToken);
        try (var stream = open(shortOwner, null)) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            while (stream.nextFrame() != null) { }
            assertThat(Instant.now()).isAfterOrEqualTo(expiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        }
        assertThat(call("GET", events(a), shortToken, null).statusCode()).isEqualTo(401);
        long start = System.nanoTime();
        try (var stream = reopen(a, "expired-session")) {
            assertResync(stream.nextEvent(), a, "CONNECTED");
            while (stream.nextFrame() != null) { }
        }
        assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isBetween(5500L, 10000L);
        try (var stream = reopen(a, null)) { assertResync(stream.nextEvent(), a, "CONNECTED"); }
    }

    private void assertResync(String frame, Owner owner, String reason) {
        assertThat(frame).contains("event:resync\n").doesNotContain("id:");
        String data = Arrays.stream(frame.split("\n")).filter(s -> s.startsWith("data:")).findFirst().orElseThrow().substring(5);
        assertThat(json.readTree(data)).isEqualTo(json.readTree("{\"accountId\":\"" + owner.account() + "\",\"reason\":\"" + reason + "\"}"));
    }
    private Stream open(Owner owner, String lastId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + events(owner)))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + owner.token())
                .header("X-Correlation-ID", "sse-contract-123").header("Accept", "text/event-stream");
        if (lastId != null) request.header("Last-Event-ID", lastId);
        return new Stream(http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
    }
    private Stream reopen(Owner owner, String lastId) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (true) {
            var stream = open(owner, lastId);
            if (stream.response.statusCode() == 200) return stream;
            int status = stream.response.statusCode(); stream.close();
            assertThat(status).isEqualTo(409);
            if (System.nanoTime() > until) throw new AssertionError("SSE slot not released");
            Thread.sleep(100);
        }
    }
    private static final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;
        private final BufferedReader reader;
        private final ExecutorService reads = Executors.newSingleThreadExecutor(task -> { var t=new Thread(task,"test-sse-reader");t.setDaemon(true);return t; });
        Stream(HttpResponse<InputStream> response) { this.response=response;reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8)); }
        String nextFrame() throws Exception {
            var read=reads.submit(() -> {var frame=new StringBuilder();String line;while((line=reader.readLine())!=null){if(line.isEmpty())return frame.toString();frame.append(line).append('\n');}return frame.isEmpty()?null:frame.toString();});
            try {return read.get(8,TimeUnit.SECONDS);} catch(Exception e){close();throw e;}
        }
        String nextEvent() throws Exception {String frame;while((frame=nextFrame())!=null){if(frame.startsWith("event:"))return frame;}throw new AssertionError("Stream closed before event");}
        public void close() throws IOException {response.body().close();reads.shutdownNow();}
    }
    private JsonNode balance(Owner o) throws Exception {var r=call("GET","/api/v1/ledger/accounts/"+o.account()+"/balance",o.token(),null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body());}
    private long countJournals(Owner o) {return jdbc.sql("SELECT count(DISTINCT journal_id) FROM banking.ledger_postings WHERE ledger_account_id=:id").param("id",o.ledger()).query(Long.class).single();}
    private String operator() throws Exception {return TOKENS.token(c->c.subject("sse-operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));}
    private HttpResponse<String> cashIn(Owner o,String amount)throws Exception{return withKey("/api/v1/operations/cash-ins",operator(),UUID.randomUUID().toString(),"{\"accountId\":\""+o.account()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}");}
    private HttpResponse<String> call(String method,String path,String token,String body)throws Exception{return send(method,path,token,null,body);}
    private HttpResponse<String> withKey(String path,String token,String key,String body)throws Exception{return send("POST",path,token,key,body);}
    private HttpResponse<String> send(String method,String path,String token,String key,String body)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));
        if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");
        return vn.banklab.core.support.ApprovedOperations.send(http,r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),TOKENS);
    }
    private Owner owner(boolean provisioned)throws Exception{
        UUID customer=UUID.randomUUID(),bank=UUID.randomUUID(),ledger=UUID.randomUUID();
        new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic SSE',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",bank).param("customer",customer).update();
            if(provisioned){jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic SSE','LIABILITY','CREDIT','VND',:bank,now())").param("id",ledger).param("code",ledger.toString()).param("bank",bank).update();jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",bank).update();}
            return null;
        });return new Owner(bank,provisioned?ledger:null,customer.toString(),TOKENS.token(c->c.subject(customer.toString())));
    }
    private void post(Owner o,Instant time){
        UUID journal=UUID.randomUUID();
        jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','fixture','sse-fixture',:at)").param("id",journal).param("ref",UUID.randomUUID()).param("at",Timestamp.from(time)).update();
        for(var posting:List.of(Map.entry(UUID.fromString("00000000-0000-0000-0000-000000000001"),"DEBIT"),Map.entry(o.ledger(),"CREDIT")))
            jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:journal,:ledger,'VND',:side,1)").param("id",UUID.randomUUID()).param("journal",journal).param("ledger",posting.getKey()).param("side",posting.getValue()).update();
        jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=created_at WHERE id=:id").param("id",journal).update();
    }
}
