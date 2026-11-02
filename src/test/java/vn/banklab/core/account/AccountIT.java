package vn.banklab.core.account;

import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class AccountIT {
    static final SignedTokenServer TOKENS;

    static {
        try {
            TOKENS = new SignedTokenServer();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("bank.iam.issuer-uri", TOKENS::issuer);
        registry.add("bank.iam.jwk-set-uri", TOKENS::jwks);
        registry.add("bank.iam.audience", () -> "bank-api");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterAll
    static void closeTokens() {
        TOKENS.close();
    }

    @Test
    void openingRequiresVerifiedCustomerAndDoesNotCreateMoney() throws Exception {
        var missing = token(UUID.randomUUID().toString(), "CUSTOMER");
        problem(open(missing), 404, "CUSTOMER_NOT_FOUND");
        assertThat(tree(call("GET", "/api/v1/accounts/me", missing, null)).isEmpty()).isTrue();
        var pending = profile(false);
        problem(open(pending.token()), 422, "KYC_NOT_VERIFIED");
        var reject = call("POST", "/api/v1/operations/customers/" + pending.customerId() + "/kyc-decisions",
                operator(), "{\"expectedVersion\":0,\"decision\":\"REJECTED\",\"reason\":\"INCOMPLETE\"}");
        assertThat(reject.statusCode()).isEqualTo(200);
        problem(open(pending.token()), 422, "KYC_NOT_VERIFIED");
        var owner = profile(true);
        var response = open(owner.token());
        assertThat(response.statusCode()).isEqualTo(201);
        var account = tree(response);
        assertThat(account.path("accountNumber").asString()).matches("[0-9]{12}");
        assertThat(account.path("currency").asString()).isEqualTo("VND");
        assertThat(account.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(account.path("version").asLong()).isZero();
        assertThat(response.body()).doesNotContain("balance", "ledgerProvisioned", "identitySubject", owner.subject());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String id = account.path("id").asString();
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/accounts/" + id);
        assertThat(tree(call("GET", "/api/v1/accounts/" + id, owner.token(), null))).isEqualTo(account);
        assertThat(tree(call("GET", "/api/v1/accounts/me", owner.token(), null)).get(0)).isEqualTo(account);
        assertThat(historyCount(UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void ownershipAndMethodSecurityRejectWrongCaller() throws Exception {
        var owner = opened();
        String other = profile(true).token();
        problem(call("GET", "/api/v1/accounts/" + owner.accountId(), other, null), 404, "ACCOUNT_NOT_FOUND");
        problem(command(owner.accountId(), "close", other, 0, "CUSTOMER_REQUEST", false), 404, "ACCOUNT_NOT_FOUND");
        problem(open(null), 401, "UNAUTHENTICATED");
        problem(open(operator()), 403, "ACCESS_DENIED");
        problem(command(owner.accountId(), "freeze", owner.token(), 0, "SECURITY_REVIEW", true), 403, "ACCESS_DENIED");
        problem(command(owner.accountId(), "close", operator(), 0, "CUSTOMER_REQUEST", false), 403, "ACCESS_DENIED");
        problem(command(owner.accountId(), "freeze", token(owner.subject(), "CUSTOMER", "OPERATOR"),
                0, "SECURITY_REVIEW", true), 422, "ACCOUNT_SELF_OPERATION_FORBIDDEN");
    }

    @Test
    void lifecyclePersistsActorHistoryAndClosedIsTerminal() throws Exception {
        var owner = opened();
        String operator = operator();
        assertThat(call("GET", "/api/v1/operations/accounts/" + owner.accountId(), operator, null).statusCode()).isEqualTo(200);
        assertState(command(owner.accountId(), "freeze", operator, 0, "SECURITY_REVIEW", true), "FROZEN", 1);
        problem(command(owner.accountId(), "close", owner.token(), 1, "CUSTOMER_REQUEST", false), 409, "ACCOUNT_STATE_INVALID");
        assertState(command(owner.accountId(), "unfreeze", operator, 1, "REVIEW_CLEARED", true), "ACTIVE", 2);
        assertState(command(owner.accountId(), "close", owner.token(), 2, "CUSTOMER_REQUEST", false), "CLOSED", 3);
        problem(command(owner.accountId(), "close", owner.token(), 2, "CUSTOMER_REQUEST", false), 409, "ACCOUNT_VERSION_CONFLICT");
        problem(command(owner.accountId(), "close", owner.token(), 3, "CUSTOMER_REQUEST", false), 409, "ACCOUNT_STATE_INVALID");
        problem(command(owner.accountId(), "freeze", operator, 3, "SECURITY_REVIEW", true), 409, "ACCOUNT_STATE_INVALID");
        problem(open(owner.token()), 409, "ACCOUNT_ALREADY_EXISTS");
        var history = jdbc.sql("SELECT to_status FROM banking.account_state_changes WHERE account_id=:id ORDER BY account_version")
                .param("id", owner.accountId()).query(String.class).list();
        assertThat(history).containsExactly("ACTIVE", "FROZEN", "ACTIVE", "CLOSED");
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.account_state_changes WHERE account_id=:id AND account_version=3")
                .param("id", owner.accountId()).query(String.class).single()).isEqualTo(owner.subject());
    }

    @Test
    void provisioningMarkerCannotBeForgedWithoutLedgerLink() throws Exception {
        var owner = opened();
        assertThatThrownBy(() -> jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=TRUE WHERE id=:id")
                .param("id", owner.accountId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertStored(owner.accountId(), "ACTIVE", 0);
        assertThat(historyCount(owner.accountId())).isEqualTo(1);
    }

    @Test
    void concurrentOpeningAndAccountNumbersAreUnique() throws Exception {
        var owner = profile(true);
        var responses = concurrent(8, () -> open(owner.token()));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsOnly(201, 409)
                .filteredOn(status -> status == 201).hasSize(1);
        for (var response : responses) {
            if (response.statusCode() == 409) problem(response, 409, "ACCOUNT_ALREADY_EXISTS");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM banking.accounts WHERE customer_id=:id").param("id", owner.customerId())
                .query(Long.class).single()).isEqualTo(1);
        String first = tree(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow()).path("accountNumber").asString();
        var second = opened();
        assertThat(tree(call("GET", "/api/v1/accounts/" + second.accountId(), second.token(), null)).path("accountNumber").asString()).isNotEqualTo(first);
    }

    @Test
    void concurrentFreezeAllowsOneWinnerAndOneHistoryChange() throws Exception {
        var owner = opened();
        String operator = operator();
        var responses = concurrent(8, () -> command(owner.accountId(), "freeze", operator, 0, "SECURITY_REVIEW", true));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsOnly(200, 409)
                .filteredOn(status -> status == 200).hasSize(1);
        for (var response : responses)
            if (response.statusCode() == 409) problem(response, 409, "ACCOUNT_VERSION_CONFLICT");
        assertStored(owner.accountId(), "FROZEN", 1);
        assertThat(historyCount(owner.accountId())).isEqualTo(2);
    }

    @Test
    void competingFreezeAndCloseCannotBothSucceed() throws Exception {
        var owner = opened();
        String operator = operator();
        var counter = new AtomicInteger();
        var responses = concurrent(2, () -> counter.getAndIncrement() == 0
                ? command(owner.accountId(), "freeze", operator, 0, "SECURITY_REVIEW", true)
                : command(owner.accountId(), "close", owner.token(), 0, "CUSTOMER_REQUEST", false));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        String winner = tree(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow()).path("status").asString();
        assertStored(owner.accountId(), winner, 1);
        assertThat(historyCount(owner.accountId())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT to_status FROM banking.account_state_changes WHERE account_id=:id AND account_version=1")
                .param("id", owner.accountId()).query(String.class).single()).isEqualTo(winner);
    }

    @Test
    void historyFailureRollsBackStateChange() throws Exception {
        var owner = opened();
        jdbc.sql("ALTER TABLE banking.account_state_changes ADD CONSTRAINT test_history_failure CHECK (account_id <> '" + owner.accountId() + "'::uuid OR account_version=0)").update();
        try {
            problem(command(owner.accountId(), "freeze", operator(), 0, "SECURITY_REVIEW", true), 500, "INTERNAL_ERROR");
            assertStored(owner.accountId(), "ACTIVE", 0);
            assertThat(historyCount(owner.accountId())).isEqualTo(1);
        } finally {
            jdbc.sql("ALTER TABLE banking.account_state_changes DROP CONSTRAINT test_history_failure").update();
        }
    }

    @Test
    void historyFailureRollsBackAccountOpening() throws Exception {
        var owner = profile(true);
        jdbc.sql("ALTER TABLE banking.account_state_changes ADD CONSTRAINT test_open_failure CHECK (actor_subject <> '" + owner.subject() + "')").update();
        try {
            problem(open(owner.token()), 500, "INTERNAL_ERROR");
            assertThat(jdbc.sql("SELECT count(*) FROM banking.accounts WHERE customer_id=:id").param("id", owner.customerId())
                    .query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("ALTER TABLE banking.account_state_changes DROP CONSTRAINT test_open_failure").update();
        }
        assertThat(open(owner.token()).statusCode()).isEqualTo(201);
    }

    @ParameterizedTest(name = "invalid open input {index}")
    @MethodSource("invalidOpenRequests")
    void invalidOpenRequestsCannotMassAssignStateOrOwner(String body) throws Exception {
        var response = call("POST", "/api/v1/accounts", token(UUID.randomUUID().toString(), "CUSTOMER"), body);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    static Stream<String> invalidOpenRequests() {
        return Stream.of("{}", "{\"currency\":\"USD\"}", "{\"currency\":\"VND\",\"customerId\":\"forged\"}",
                "{\"currency\":\"VND\",\"ledgerProvisioned\":false}", "{\"currency\":\"VND\",\"status\":\"CLOSED\"}");
    }

    @Test
    void invalidReasonAndPayloadDoNotChangeState() throws Exception {
        var owner = opened();
        String operator = operator();
        problem(command(owner.accountId(), "freeze", operator, 0, "REVIEW_CLEARED", true), 422, "ACCOUNT_REASON_INVALID");
        var bad = call("POST", "/api/v1/operations/accounts/" + owner.accountId() + "/freeze", operator, "{\"expectedVersion\":-1,\"reason\":\"SECURITY_REVIEW\"}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertStored(owner.accountId(), "ACTIVE", 0);
    }

    @Test
    void databaseConstraintsRejectInvalidNullHistoryAndUpgradeV2KeepsCustomers() throws Exception {
        var owner = opened();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO banking.account_state_changes
                (id,account_id,from_status,to_status,reason,actor_issuer,actor_subject,account_version,changed_at)
                VALUES (:id,:account,NULL,'CLOSED','OPENED','issuer','subject',99,now())
                """).param("id", UUID.randomUUID()).param("account", owner.accountId()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            s.execute("CREATE DATABASE account_upgrade");
        }
        String url = POSTGRES.getJdbcUrl().replace("/test", "/account_upgrade");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("2").load().migrate();
        try (var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            s.execute("INSERT INTO banking.customers (id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES ('" + UUID.randomUUID() + "','issuer','preserved','Preserved Demo',now(),now())");
        }
        var flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement(); var r = s.executeQuery("SELECT display_name FROM banking.customers WHERE identity_subject='preserved'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("Preserved Demo");
        }
    }

    @Test
    void openApiHasAccountOperationsWithoutBalanceOrInternalMarker() throws Exception {
        var d = tree(call("GET", "/v3/api-docs", null, null));
        assertThat(d.path("paths").path("/api/v1/accounts").path("post").path("responses").has("201")).isTrue();
        assertThat(d.path("paths").path("/api/v1/accounts/{id}/close").path("post").path("responses").has("422")).isTrue();
        assertThat(d.path("paths").has("/api/v1/operations/accounts/{id}/freeze")).isTrue();
        assertThat(d.path("components").path("schemas").path("AccountResponse").path("properties").has("ledgerProvisioned")).isFalse();
    }

    private record Owner(String subject, String token, UUID customerId, UUID accountId) {
    }

    private Owner profile(boolean verified) throws Exception {
        String subject = UUID.randomUUID().toString(), token = token(subject, "CUSTOMER");
        var response = call("POST", "/api/v1/customers", token, "{\"displayName\":\"Account Demo\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(tree(response).path("id").asString());
        if (verified) assertThat(call("POST", "/api/v1/operations/customers/" + id + "/kyc-decisions", operator(),
                "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}").statusCode()).isEqualTo(200);
        return new Owner(subject, token, id, null);
    }

    private Owner opened() throws Exception {
        var owner = profile(true);
        var response = open(owner.token());
        assertThat(response.statusCode()).isEqualTo(201);
        return new Owner(owner.subject(), owner.token(), owner.customerId(), UUID.fromString(tree(response).path("id").asString()));
    }

    private static String token(String subject, String... roles) throws Exception {
        return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles)))));
    }

    private static String operator() throws Exception {
        return token(UUID.randomUUID().toString(), "OPERATOR");
    }

    private HttpResponse<String> open(String token) throws Exception {
        return call("POST", "/api/v1/accounts", token, "{\"currency\":\"VND\"}");
    }

    private HttpResponse<String> command(UUID id, String action, String token, long version, String reason, boolean operations) throws Exception {
        return call("POST", "/api/v1/" + (operations ? "operations/" : "") + "accounts/" + id + "/" + action, token,
                "{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\"}");
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25)).header("X-Correlation-ID", "account-005");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode tree(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    private void problem(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(tree(r).path("code").asString()).isEqualTo(code);
        assertThat(tree(r).path("correlationId").asString()).isEqualTo("account-005");
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
    }

    private void assertState(HttpResponse<String> r, String state, long version) {
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(tree(r).path("status").asString()).isEqualTo(state);
        assertThat(tree(r).path("version").asLong()).isEqualTo(version);
    }

    private void assertStored(UUID id, String status, long version) {
        assertThat(jdbc.sql("SELECT status,version FROM banking.accounts WHERE id=:id").param("id", id).query().singleRow())
                .containsEntry("status", status).containsEntry("version", version);
    }

    private long historyCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM banking.account_state_changes WHERE account_id=:id").param("id", id).query(Long.class).single();
    }

    private static List<HttpResponse<String>> concurrent(int count, Callable<HttpResponse<String>> call) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var fs = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++)
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var result = new ArrayList<HttpResponse<String>>();
            for (var f : fs) result.add(f.get(40, TimeUnit.SECONDS));
            return result;
        }
    }
}
