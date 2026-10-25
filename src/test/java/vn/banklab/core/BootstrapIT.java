package vn.banklab.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Test
    void startsWithPostgresAndMigratesExactlyOnce() throws Exception {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
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
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test
    void servesSwaggerAssetsAndGeneratedApiContract() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/api/v1/system/info", "getSystemInfo", "SystemInfoResponse");
        assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
        var ui = get("/swagger-ui.html");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("swagger-ui");
        assertThat(get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
