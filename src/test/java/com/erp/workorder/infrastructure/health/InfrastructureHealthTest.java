package com.erp.workorder.infrastructure.health;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readiness REALLY probes the database.
 *
 * <p>While PostgreSQL answers, /actuator/health and /actuator/health/readiness
 * report UP; the instant the database dies, the readiness turns DOWN and the
 * endpoints answer 503 — an orchestrator reading them stops sending traffic.
 * This test carries its OWN PostgreSQL, because it stops it mid-test and the
 * rest of the suites must keep theirs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InfrastructureHealthTest {

    /** This suite's own PostgreSQL, killed inside the test. */
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void registerOwnPostgresProperties(DynamicPropertyRegistry registry) {
        try {
            postgres = EmbeddedPostgres.builder()
                    .setServerConfig("fsync", "off")
                    .setServerConfig("full_page_writes", "off")
                    .setServerConfig("synchronous_commit", "off")
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("The health test's PostgreSQL could not start", e);
        }
        String url = postgres.getJdbcUrl("postgres", "postgres").split("[?]")[0];
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        // The migrations' placeholder for the execution role; the health suite
        // never exercises the RLS roles of the platform.
        registry.add("spring.flyway.placeholders.appRolePassword",
                () -> "health_test_placeholder_password");
        registry.add("spring.flyway.placeholders.platformRolePassword",
                () -> "health_test_placeholder_platform");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @AfterAll
    static void stopTheKilledInstance() {
        if (postgres != null) {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // it is already dead or was closed inside the test
            }
        }
    }

    @Autowired
    private WebServerApplicationContext server;

    private RestClient rest() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getWebServer().getPort())
                .build();
    }

    @Test
    @Order(1)
    void healthyDatabaseMeansReadyAndUp() {
        ResponseEntity<String> base = rest().get().uri("/actuator/health").retrieve()
                .toEntity(String.class);
        ResponseEntity<String> readiness = rest().get().uri("/actuator/health/readiness").retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.OK, base.getStatusCode());
        assertEquals(HttpStatus.OK, readiness.getStatusCode());
        assertTrue(readiness.getBody() != null && readiness.getBody().contains("\"UP\""),
                "the readiness reports UP with the database answering");
    }

    @Test
    @Order(2)
    void aDeadDatabaseTurnsTheReadinessDownAndTheEndpointAnswersServiceUnavailable() throws Exception {
        // Run at least one query through the pool so the failure of the next
        // one comes from the DATABASE, not from a cold pool never used.
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }

        // The database dies under the service's feet.
        postgres.close();

        ResponseEntity<String> base = healthAfterFailure("/actuator/health");
        ResponseEntity<String> readiness = healthAfterFailure("/actuator/health/readiness");


        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, base.getStatusCode(),
                "the whole health turns DOWN when the service cannot serve");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readiness.getStatusCode(),
                "the readiness reports the failure the orchestrator acts on");
        assertTrue(readiness.getBody() != null && readiness.getBody().contains("\"DOWN\""),
                "the readiness reports DOWN");
    }

    /** The actuator answers 503 WITH a body: the retrieval must not throw on the status. */
    private ResponseEntity<String> healthAfterFailure(String path) {
        return rest().get().uri(path).exchange((request, response) -> ResponseEntity
                .status(response.getStatusCode())
                .body(new String(response.getBody().readAllBytes())));
    }
}
