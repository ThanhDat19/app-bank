package vn.banklab.core.customer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
class CustomerIT {
    static final SignedTokenServer TOKENS;
    static {
        try { TOKENS = new SignedTokenServer(); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
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

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterAll static void closeTokenServer() { TOKENS.close(); }

    @Test
    void createsOwnedProfileAndDoesNotExposeIdentityLinks() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = token(subject, "CUSTOMER");
        problem(request("GET", "/api/v1/customers/me", token, null), 404, "CUSTOMER_NOT_FOUND");
        var response = create(token, "  Demo Customer A  ");
        assertThat(response.statusCode()).isEqualTo(201);
        var customer = tree(response);
        String id = customer.path("id").asString();
        assertThat(customer.path("displayName").asString()).isEqualTo("Demo Customer A");
        assertThat(customer.path("kycStatus").asString()).isEqualTo("PENDING");
        assertThat(customer.path("version").asLong()).isZero();
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/customers/" + id);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(subject, "identityIssuer", "identitySubject", "access_token");
        assertThat(tree(request("GET", "/api/v1/customers/me", token, null))).isEqualTo(customer);
        assertThat(tree(request("GET", "/api/v1/customers/" + id, token, null))).isEqualTo(customer);
        assertThat(jdbc.sql("SELECT identity_subject FROM banking.customers WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo(subject);
        String other = token(UUID.randomUUID().toString(), "CUSTOMER");
        problem(request("GET", "/api/v1/customers/" + id, other, null), 404, "CUSTOMER_NOT_FOUND");
        problem(request("GET", "/api/v1/customers/" + UUID.randomUUID(), other, null), 404, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void rejectsAnonymousAndWrongRolesThroughRealSecurityChain() throws Exception {
        String customer = token(UUID.randomUUID().toString(), "CUSTOMER");
        String operator = token(UUID.randomUUID().toString(), "OPERATOR");
        problem(request("POST", "/api/v1/customers", null, "{\"displayName\":\"Demo\"}"), 401, "UNAUTHENTICATED");
        problem(create(operator, "Demo"), 403, "ACCESS_DENIED");
        problem(request("GET", "/api/v1/customers/me", operator, null), 403, "ACCESS_DENIED");
        problem(request("GET", "/api/v1/operations/customers/" + UUID.randomUUID(), customer, null), 403, "ACCESS_DENIED");
        problem(request("POST", "/api/v1/operations/customers/" + UUID.randomUUID() + "/kyc-decisions",
                customer, decision(0, "VERIFIED", "DOCUMENTS_MATCH")), 403, "ACCESS_DENIED");
    }

    @ParameterizedTest(name = "invalid customer input {index}")
    @MethodSource("invalidProfiles")
    void rejectsInvalidProfileWithoutEchoingInput(String payload) throws Exception {
        var response = request("POST", "/api/v1/customers", token(UUID.randomUUID().toString(), "CUSTOMER"), payload);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("do-not-echo", "forged-subject");
    }

    static Stream<String> invalidProfiles() {
        return Stream.of("{}", "{\"displayName\":\" \"}", "{\"displayName\":\"x\"}",
                "{\"displayName\":\"" + "do-not-echo".repeat(10) + "\"}",
                "{\"displayName\":\"Demo\\nName\"}",
                "{\"displayName\":\"Demo\",\"identitySubject\":\"forged-subject\"}",
                "{\"displayName\":\"Demo\",\"kycStatus\":\"VERIFIED\"}");
    }

    @Test
    void concurrentCreatesProduceOneProfileWithStableConflict() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = token(subject, "CUSTOMER");
        var responses = concurrent(8, () -> create(token, "Concurrent Demo"));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsOnly(201, 409).filteredOn(status -> status == 201).hasSize(1);
        for (var response : responses) {
            if (response.statusCode() == 409) { problem(response, 409, "CUSTOMER_ALREADY_EXISTS"); }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM banking.customers WHERE identity_issuer = :issuer AND identity_subject = :subject")
                .param("issuer", TOKENS.issuer()).param("subject", subject).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void identityUniquenessIncludesIssuer() throws Exception {
        String subject = UUID.randomUUID().toString();
        create(token(subject, "CUSTOMER"), "Issuer A Demo");
        jdbc.sql("""
                INSERT INTO banking.customers (id, identity_issuer, identity_subject, display_name, created_at, updated_at)
                VALUES (:id, 'https://other-idp.invalid', :subject, 'Issuer B Demo', now(), now())
                """).param("id", UUID.randomUUID()).param("subject", subject).update();
        var mine = tree(request("GET", "/api/v1/customers/me", token(subject, "CUSTOMER"), null));
        assertThat(mine.path("displayName").asString()).isEqualTo("Issuer A Demo");
    }

    @Test
    void operatorDecisionCommitsVersionAndHistoryAndCannotBeRepeated() throws Exception {
        UUID id = createId(token(UUID.randomUUID().toString(), "CUSTOMER"));
        String reviewer = UUID.randomUUID().toString();
        String operator = token(reviewer, "OPERATOR");
        assertThat(request("GET", "/api/v1/operations/customers/" + id, operator, null).statusCode()).isEqualTo(200);
        var response = decide(id, operator, decision(0, "VERIFIED", "DOCUMENTS_MATCH"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(tree(response).path("kycStatus").asString()).isEqualTo("VERIFIED");
        assertThat(tree(response).path("version").asLong()).isEqualTo(1);
        var history = jdbc.sql("SELECT * FROM banking.kyc_decisions WHERE customer_id = :id")
                .param("id", id).query().singleRow();
        assertThat(history).containsEntry("reviewer_subject", reviewer).containsEntry("reviewer_issuer", TOKENS.issuer())
                .containsEntry("from_status", "PENDING").containsEntry("to_status", "VERIFIED")
                .containsEntry("reason", "DOCUMENTS_MATCH").containsEntry("customer_version", 1L);
        problem(decide(id, operator, decision(0, "VERIFIED", "DOCUMENTS_MATCH")), 409, "CUSTOMER_VERSION_CONFLICT");
        problem(decide(id, operator, decision(1, "REJECTED", "INCOMPLETE")), 409, "KYC_ALREADY_DECIDED");
        assertThat(historyCount(id)).isEqualTo(1);
    }

    @Test
    void rejectsSelfReviewAndInvalidReasonWithoutChanges() throws Exception {
        String subject = UUID.randomUUID().toString();
        UUID id = createId(token(subject, "CUSTOMER"));
        problem(decide(id, token(subject, "CUSTOMER", "OPERATOR"), decision(0, "VERIFIED", "DOCUMENTS_MATCH")),
                422, "KYC_SELF_REVIEW_FORBIDDEN");
        String operator = token(UUID.randomUUID().toString(), "OPERATOR");
        problem(decide(id, operator, decision(0, "VERIFIED", "INCOMPLETE")), 422, "KYC_REASON_INVALID");
        assertPending(id);
        assertThat(historyCount(id)).isZero();
        assertThat(decide(id, operator, decision(0, "REJECTED", "INCONSISTENT")).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT kyc_status FROM banking.customers WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("REJECTED");
    }

    @ParameterizedTest(name = "invalid KYC input {index}")
    @MethodSource("invalidDecisions")
    void invalidKycRequestsCannotChangeState(String payload) throws Exception {
        UUID id = createId(token(UUID.randomUUID().toString(), "CUSTOMER"));
        var response = decide(id, token(UUID.randomUUID().toString(), "OPERATOR"), payload);
        assertThat(response.statusCode()).isEqualTo(400);
        assertPending(id);
        assertThat(historyCount(id)).isZero();
    }

    static Stream<String> invalidDecisions() {
        return Stream.of("{}", decision(-1, "VERIFIED", "DOCUMENTS_MATCH"), decision(0, "PENDING", "DOCUMENTS_MATCH"),
                decision(0, "VERIFIED", "FREE_TEXT"),
                "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\",\"reviewerSubject\":\"forged\"}");
    }

    @Test
    void concurrentDecisionsAllowOneWinnerAndOneHistoryRow() throws Exception {
        UUID id = createId(token(UUID.randomUUID().toString(), "CUSTOMER"));
        String operator = token(UUID.randomUUID().toString(), "OPERATOR");
        var responses = concurrent(8, () -> decide(id, operator, decision(0, "VERIFIED", "DOCUMENTS_MATCH")));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsOnly(200, 409).filteredOn(status -> status == 200).hasSize(1);
        for (var response : responses) {
            if (response.statusCode() == 409) { problem(response, 409, "CUSTOMER_VERSION_CONFLICT"); }
        }
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM banking.customers WHERE id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void historyWriteFailureRollsBackProfileUpdate() throws Exception {
        UUID id = createId(token(UUID.randomUUID().toString(), "CUSTOMER"));
        // Fault injection is local to this test database; no test failure switch in production code.
        jdbc.sql("ALTER TABLE banking.kyc_decisions ADD CONSTRAINT test_history_failure CHECK (customer_id <> '" + id + "'::uuid)").update();
        try {
            problem(decide(id, token(UUID.randomUUID().toString(), "OPERATOR"), decision(0, "VERIFIED", "DOCUMENTS_MATCH")),
                    500, "INTERNAL_ERROR");
            assertPending(id);
            assertThat(historyCount(id)).isZero();
        } finally {
            jdbc.sql("ALTER TABLE banking.kyc_decisions DROP CONSTRAINT test_history_failure").update();
        }
    }

    @Test
    void migrationUpgradesExistingV1WithoutLosingDataAndConstraintsProtectWrites() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE customer_upgrade");
        }
        String url = POSTGRES.getJdbcUrl().replace("/test", "/customer_upgrade");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.upgrade_marker (value TEXT); INSERT INTO public.upgrade_marker VALUES ('preserved')");
        }
        var flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT value FROM public.upgrade_marker")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("preserved");
        }
        UUID id = createId(token(UUID.randomUUID().toString(), "CUSTOMER"));
        assertThatThrownBy(() -> jdbc.sql("UPDATE banking.customers SET kyc_status = 'UNKNOWN' WHERE id = :id").param("id", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertPending(id);
    }

    @Test
    void generatedOpenApiDocumentsCustomerAndDecisionContracts() throws Exception {
        var document = tree(request("GET", "/v3/api-docs", null, null));
        var create = document.path("paths").path("/api/v1/customers").path("post");
        assertThat(create.path("responses").has("201")).isTrue();
        assertThat(create.path("responses").has("409")).isTrue();
        assertThat(create.path("requestBody").path("content").has("application/json")).isTrue();
        var decide = document.path("paths").path("/api/v1/operations/customers/{id}/kyc-decisions").path("post");
        assertThat(decide.path("responses").has("422")).isTrue();
        assertThat(decide.path("responses").has("409")).isTrue();
        assertThat(document.path("components").path("schemas").path("CustomerResponse").path("properties").has("identitySubject")).isFalse();
    }

    private void assertPending(UUID id) {
        var row = jdbc.sql("SELECT kyc_status, version FROM banking.customers WHERE id = :id").param("id", id).query().singleRow();
        assertThat(row).containsEntry("kyc_status", "PENDING").containsEntry("version", 0L);
    }

    private long historyCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM banking.kyc_decisions WHERE customer_id = :id").param("id", id).query(Long.class).single();
    }

    private UUID createId(String token) throws Exception {
        var response = create(token, "Demo Customer");
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(tree(response).path("id").asString());
    }

    private HttpResponse<String> create(String token, String name) throws Exception {
        return request("POST", "/api/v1/customers", token, json.writeValueAsString(Map.of("displayName", name)));
    }

    private HttpResponse<String> decide(UUID id, String token, String body) throws Exception {
        return request("POST", "/api/v1/operations/customers/" + id + "/kyc-decisions", token, body);
    }

    private static String decision(long version, String decision, String reason) {
        return "{\"expectedVersion\":" + version + ",\"decision\":\"" + decision + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String token(String subject, String... roles) throws Exception {
        return TOKENS.token(claims -> claims.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles)))));
    }

    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("X-Correlation-ID", "customer-004");
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(tree(response).path("code").asString()).isEqualTo(code);
        assertThat(tree(response).path("correlationId").asString()).isEqualTo("customer-004");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("customer-004");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
    }

    private static List<HttpResponse<String>> concurrent(int count, java.util.concurrent.Callable<HttpResponse<String>> call) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return call.call(); }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) { responses.add(future.get(30, TimeUnit.SECONDS)); }
            return responses;
        }
    }
}
