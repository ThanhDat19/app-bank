package vn.banklab.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.nimbusds.jwt.PlainJWT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.shared.web.ApiErrorResponses;
import vn.banklab.core.support.SignedTokenServer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BootstrapIT.SecurityProbe.class)
class BootstrapIT {

    static final SignedTokenServer TOKENS;

    static {
        try {
            TOKENS = new SignedTokenServer();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("bank.iam.issuer-uri", TOKENS::issuer);
        registry.add("bank.iam.jwk-set-uri", TOKENS::jwks);
        registry.add("bank.iam.audience", () -> SignedTokenServer.AUDIENCE);
    }

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Test
    void startsWithPostgresAndMigratesExactlyOnce() throws Exception {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.schemata where schema_name = 'banking'");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void exposesHealthAndSystemContractOverHttp() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("jdbc:", "password");
        var info = get("/api/v1/system/info");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).contains("\"mode\":\"SIMULATION\"");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void servesSwaggerAssetsAndGeneratedApiContract() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/api/v1/system/info", "getSystemInfo", "SystemInfoResponse");
        var document = JsonMapper.builder().build().readTree(docs.body());
        var operation = document.path("paths").path("/api/v1/system/info").path("get");
        assertThat(operation.path("responses").path("default").path("content")
                .path("application/problem+json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiProblem");
        assertThat(operation.path("responses").path("200").path("headers").has("X-Correlation-ID")).isTrue();
        assertThat(operation.path("parameters").toString()).contains("X-Correlation-ID");
        for (var parameter : operation.path("parameters")) {
            if (parameter.path("name").asString().equals("X-Correlation-ID")) {
                var pattern = java.util.regex.Pattern.compile(parameter.path("schema").path("pattern").asString());
                // JSON Schema pattern performs a search, unlike Java Matcher.matches().
                assertThat(pattern.matcher("review-002.A_1").find()).isTrue();
                assertThat(pattern.matcher("contains space").find()).isFalse();
            }
        }
        assertThat(document.path("components").path("schemas").path("ApiProblem").path("properties")
                .has("correlationId")).isTrue();
        assertThat(document.path("components").path("securitySchemes").path("oidc").path("flows")
                .path("authorizationCode").path("authorizationUrl").asString()).startsWith(TOKENS.issuer());
        assertThat(operation.path("security").isEmpty()).isTrue();
        assertThat(document.path("paths").path("/api/v1/iam/me").path("get").path("responses").has("401")).isTrue();
        assertThat(get("/swagger-ui/swagger-initializer.js").body())
                .containsPattern("usePkceWithAuthorizationCodeGrant\"?\\s*:\\s*true");
        assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
        var ui = get("/swagger-ui.html");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("swagger-ui");
        assertThat(get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
    }

    @Test
    void errorContractMatchesCorrelationHeaderOverRealHttp() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/missing?token=do-not-expose"))
                .header("Authorization", "Bearer " + TOKENS.token())
                .header("X-Correlation-ID", "integration-002").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("integration-002");
        var problem = JsonMapper.builder().build().readTree(response.body());
        assertThat(problem.path("code").asString()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(problem.path("correlationId").asString()).isEqualTo("integration-002");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("do-not-expose");
        assertThat(get("/api/v1/system/info").headers().firstValue("X-Correlation-ID").orElseThrow())
                .isNotEqualTo("integration-002");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                // OpenAPI is generated lazily; cold schema generation is not a request-latency SLO.
                .timeout(java.time.Duration.ofSeconds(path.startsWith("/v3/api-docs") ? 60 : 15))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void stopTokenServer() {
        TOKENS.close();
    }

    @Test
    void missingTokenAndIdentityHeadersCannotAuthenticate() throws Exception {
        var response = request("/api/v1/iam/me", null, Map.of("X-User-Id", "forged-actor", "X-Roles", "OPERATOR"));
        assertProblem(response, 401, "UNAUTHENTICATED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void verifiedActorComesFromTokenAndNotSpoofedHeaders() throws Exception {
        var response = request("/api/v1/iam/me", TOKENS.token(), Map.of("X-User-Id", "forged-actor", "X-Roles", "OPERATOR"));
        assertThat(response.statusCode()).isEqualTo(200);
        var actor = JsonMapper.builder().build().readTree(response.body());
        assertThat(actor.path("issuer").asString()).isEqualTo(TOKENS.issuer());
        assertThat(actor.path("subject").asString()).isEqualTo(SignedTokenServer.SUBJECT);
        assertThat(actor.path("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).doesNotContain("forged-actor", "access_token", "email");
    }

    @ParameterizedTest(name = "untrusted token case {index}")
    @MethodSource("invalidTokens")
    void untrustedTokensAreRejectedWithoutDetails(String token) throws Exception {
        var response = request("/api/v1/iam/me", token, Map.of());
        assertProblem(response, 401, "UNAUTHENTICATED");
        assertThat(response.body()).doesNotContain(token, "JwtValidationException", "issuer", "audience");
    }

    static Stream<String> invalidTokens() throws Exception {
        return Stream.of("malformed-token", TOKENS.wrongSignature(), TOKENS.wrongAlgorithm(),
                new PlainJWT(TOKENS.claims().build()).serialize(),
                TOKENS.token(claims -> claims.issuer("https://wrong.invalid")),
                TOKENS.token(claims -> claims.audience("another-api")),
                TOKENS.token(claims -> claims.claim("aud", null)),
                TOKENS.token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(180)))),
                TOKENS.token(claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(180)))),
                TOKENS.token(claims -> claims.expirationTime(null)),
                TOKENS.token(claims -> claims.subject(null)),
                TOKENS.token(claims -> claims.subject(" ")),
                TOKENS.token(claims -> claims.claim("typ", "ID")),
                TOKENS.token(claims -> claims.claim("typ", null)));
    }

    @ParameterizedTest(name = "unprivileged token case {index}")
    @MethodSource("unprivilegedTokens")
    void rolesFromOtherClientsOrRealmDoNotGrantAccess(String token) throws Exception {
        assertProblem(request("/api/v1/iam/me", token, Map.of()), 403, "ACCESS_DENIED");
    }

    static Stream<String> unprivilegedTokens() throws Exception {
        return Stream.of(
                TOKENS.token(claims -> claims.claim("resource_access", Map.of())),
                TOKENS.token(claims -> claims.claim("resource_access", Map.of("other-api", Map.of("roles", List.of("OPERATOR"))))),
                TOKENS.token(claims -> claims.claim("resource_access", "malformed")),
                TOKENS.token(claims -> claims.claim("resource_access", Map.of("bank-api", Map.of("roles", List.of("ADMIN"))))),
                TOKENS.token(claims -> claims.claim("resource_access", Map.of()).claim("realm_access", Map.of("roles", List.of("OPERATOR")))));
    }

    @Test
    void operatorPolicyAndMethodSecurityHaveConsistent403() throws Exception {
        String customer = TOKENS.token();
        String operator = TOKENS.token(claims -> claims.claim("resource_access",
                Map.of("bank-api", Map.of("roles", List.of("OPERATOR")))));
        assertProblem(request("/api/v1/operations/probe", customer, Map.of()), 403, "ACCESS_DENIED");
        assertProblem(request("/api/v1/iam/method-probe", customer, Map.of()), 403, "ACCESS_DENIED");
        assertThat(request("/api/v1/operations/probe", operator, Map.of()).statusCode()).isEqualTo(200);
        assertThat(request("/api/v1/iam/method-probe", operator, Map.of()).statusCode()).isEqualTo(200);
        assertProblem(request("/api/v1/iam/auth-probe", customer, Map.of()), 401, "UNAUTHENTICATED");
    }

    @Test
    void protectedAndUnmappedRoutesFailClosed() throws Exception {
        assertProblem(request("/api/v1/future-feature", null, Map.of()), 401, "UNAUTHENTICATED");
        assertProblem(request("/outside-api", TOKENS.token(), Map.of()), 403, "ACCESS_DENIED");
        assertThat(get("/api/v1/system/info").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> request(String path, String token, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("X-Correlation-ID", "iam-integration");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("iam-integration");
        var problem = JsonMapper.builder().build().readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("correlationId").asString()).isEqualTo("iam-integration");
        assertThat(problem.path("errors").isArray()).isTrue();
        assertThat(problem.path("errors").isEmpty()).isTrue();
    }

    @TestComponent
    @RestController
    @ApiErrorResponses
    public static class SecurityProbe {
        @GetMapping("/api/v1/operations/probe")
        public String operator() {
            return "operator";
        }

        @GetMapping("/api/v1/iam/method-probe")
        @PreAuthorize("hasRole('OPERATOR')")
        public String methodOperator() {
            return "operator";
        }

        @GetMapping("/api/v1/iam/auth-probe")
        public String missingContext() {
            throw new AuthenticationCredentialsNotFoundException("private diagnostic");
        }
    }
}
