package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.InventoryUnavailableException;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The saga client's transport guarantees, with the wire observed directly:
 * the principal's token re-sent, the tenant header copied from the context,
 * the transient failure retried with the SAME idempotency key, and the open
 * circuit answering fast without touching the wire.
 */
class InventoryClientTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private InventoryClient client;

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    private final UUID part = UUID.randomUUID();
    private final UUID branch = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final String key = "wo:" + order + ":reserve:" + part;

    private SagaStockOperation reserve;

    @BeforeEach
    void buildTheClient() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new InventoryClient(restClientBuilder, registry, "http://inventory.test", null);
        reserve = new SagaStockOperation(part, branch, 2, order, key);

        PrincipalTokenHolder.setToken("verified.principal.token");
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void withNoResidue() {
        PrincipalTokenHolder.clear();
        TenantContextHolder.clear();
        registry.remove("inventory");
    }

    @Test
    void thePrincipalTokenTravelsInTheAuthorizationHeader() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andExpect(header("Authorization", "Bearer verified.principal.token"))
                .andExpect(header("X-Tenant-ID", TenantContextHolder.getTenantId().toString()))
                .andRespond(withSuccess().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{\"id\":\"" + UUID.randomUUID() + "\",\"movementType\":\"RESERVE\",\"quantity\":2}"));

        InventoryStockOutcome outcome = client.reserve(reserve);

        assertEquals("RESERVE", outcome.movementType());
        server.verify();
    }

    @Test
    void theSagaCallRequiresTheVerifiedTokenAndFailsClosedWithoutIt() {
        PrincipalTokenHolder.clear();

        assertThrows(InventoryUnavailableException.class, () -> client.reserve(reserve));
        server.verify();
    }

    @Test
    void aTimeoutIsRetriedOnceWithTheSameIdempotencyKey() {
        String bodyWithKey = "{\"id\":\"" + UUID.randomUUID() + "\",\"movementType\":\"RESERVE\",\"quantity\":2}";
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"" + key + "\"")))
                .andExpect(header("Authorization", "Bearer verified.principal.token"))
                .andRespond(withSuccess().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(bodyWithKey));

        InventoryStockOutcome outcome = client.reserve(reserve);

        assertEquals("RESERVE", outcome.movementType(), "the second attempt carried the unknown outcome home");
        server.verify();
    }

    @Test
    void aServerErrorResponseIsTransientAndRetried() {
        String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"movementType\":\"RESERVE\",\"quantity\":2}";
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withServerError());
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withSuccess().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body));

        InventoryStockOutcome outcome = client.reserve(reserve);

        assertEquals("RESERVE", outcome.movementType());
        server.verify();
    }

    @Test
    void twoTransientAttemptsLeaveAnAvailabilityFailure() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withServerError());

        InventoryUnavailableException failure = assertThrows(InventoryUnavailableException.class,
                () -> client.reserve(reserve));

        assertTrue(failure.getMessage().contains("after one retry"));
        server.verify();
    }

    @Test
    void theOpenCircuitAnswersFastWithoutCallingTheInventory() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry tight = CircuitBreakerRegistry.of(config);

        InventoryClient tightClient = new InventoryClient(restClientBuilder, tight, "http://inventory.test", null);

        // First failure opens the circuit for this instance (the 5xx is
        // transient, so the client retries once: TWO failing calls).
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("http://inventory.test/api/v1/inventory/stock/reserve"))
                .andRespond(withServerError());
        assertThrows(InventoryUnavailableException.class, () -> tightClient.reserve(reserve));

        CircuitBreaker breaker = tight.circuitBreaker(InventoryClient.CIRCUIT_NAME);
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // The circuit is open: the next reserve must fail fast WITHOUT a new
        // wire attempt. If the client DID call the inventory again, the mock
        // itself would fail this test with an unexpected-request error, and
        // the message would not be the circuit's.
        InventoryUnavailableException fast = assertThrows(InventoryUnavailableException.class,
                () -> tightClient.reserve(reserve));
        assertTrue(fast.getMessage().contains("circuit is open"));
        server.verify();
    }
}
