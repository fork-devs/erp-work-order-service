package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.FleetUnavailableException;
import com.erp.workorder.domain.port.FleetPort;
import com.erp.workorder.domain.port.FleetPort.VehicleRegistration;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The verification call against the fleet, with the wire observed
 * directly: the principal's token re-sent, an unknown vehicle answered as
 * an empty verification, the deadline exhausted as an availability
 * failure after ONE retry, and the open circuit answering fast.
 */
class FleetClientTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private FleetClient client;
    private UUID vehicleId;

    @BeforeEach
    void buildTheClient() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        client = new FleetClient(restClientBuilder, registry, "http://fleet.test", null);
        vehicleId = UUID.randomUUID();

        PrincipalTokenHolder.setToken("verified.principal.token");
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void withNoResidue() {
        PrincipalTokenHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void theVehicleArrivesWithItsRegisteredCustomerAndTheVerifiedToken() {
        UUID customerId = UUID.randomUUID();
        server.expect(once(), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andExpect(header("Authorization", "Bearer verified.principal.token"))
                .andExpect(header("X-Tenant-ID", TenantContextHolder.getTenantId().toString()))
                .andRespond(withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + vehicleId + "\",\"customerId\":\"" + customerId + "\"}"));

        Optional<VehicleRegistration> vehicle = client.findVehicle(UUID.randomUUID(), vehicleId);

        assertEquals(customerId, vehicle.orElseThrow().customerId());
        server.verify();
    }

    @Test
    void anUnknownVehicleIsADecisiveAnswerAndNotAnOutage() {
        server.expect(once(), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertTrue(client.findVehicle(UUID.randomUUID(), vehicleId).isEmpty());
        server.verify();
    }

    @Test
    void theVerificationCallRequiresTheVerifiedTokenAndFailsClosedWithoutIt() {
        PrincipalTokenHolder.clear();

        FleetUnavailableException failure = assertThrows(FleetUnavailableException.class,
                () -> client.findVehicle(UUID.randomUUID(), vehicleId));

        assertTrue(failure.getMessage().contains("verified principal's token"));
        server.verify();
    }

    @Test
    void aDeadlineExhaustsAfterOneRetryIntoAnAvailabilityFailure() {
        server.expect(times(2), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        FleetUnavailableException failure = assertThrows(FleetUnavailableException.class,
                () -> client.findVehicle(UUID.randomUUID(), vehicleId));

        assertTrue(failure.getMessage().contains("after one retry"));
        server.verify();
    }

    @Test
    void anOwnerlessAnswerFromAFallenFleetAlsoArrivesAfterTheRetry() {
        server.expect(once(), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(once(), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andRespond(withServerError());

        FleetUnavailableException failure = assertThrows(FleetUnavailableException.class,
                () -> client.findVehicle(UUID.randomUUID(), vehicleId));

        assertTrue(failure.getMessage().contains("after one retry"));
        server.verify();
    }

    @Test
    void theOpenCircuitAnswersFastWithoutCallingTheFleet() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry tight = CircuitBreakerRegistry.of(config);
        FleetClient tightClient = new FleetClient(restClientBuilder, tight, "http://fleet.test", null);

        // A failing call trio: transport failure once plus its retry (both
        // counted failures) open the circuit.
        server.expect(times(2), requestTo("http://fleet.test/api/v1/fleet/vehicles/" + vehicleId))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThrows(FleetUnavailableException.class, () -> tightClient.findVehicle(UUID.randomUUID(), vehicleId));

        CircuitBreaker breaker = tight.circuitBreaker(FleetClient.CIRCUIT_NAME);
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // The circuit is open: the next verification must fail fast WITHOUT
        // a new wire attempt — if the client called the fleet again, the
        // mock itself would fail this test with an unexpected-request error.
        FleetUnavailableException fast = assertThrows(FleetUnavailableException.class,
                () -> tightClient.findVehicle(UUID.randomUUID(), vehicleId));
        assertTrue(fast.getMessage().contains("circuit is open"));
        server.verify();
    }
}
