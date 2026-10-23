package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.FleetUnavailableException;
import com.erp.workorder.domain.port.FleetPort;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The verification call against the fleet, with the same wrapper as the
 * inventory client: deadline of 500 ms in connection and reading, the
 * verified principal's token re-sent in the Authorization header, one
 * retry on transient failures, and the {@code fleet} circuit breaker that
 * answers fast with its availability code when it is open. Implements the
 * domain's {@code FleetPort}.
 *
 * <p>An unknown vehicle is a DECISIVE business answer ({@code Optional}):
 * the vehicle is not in the tenant. Any other rejection (5xx, timeouts,
 * a broken transport or a disregarded authorization) is an availability
 * failure: {@code FleetUnavailableException}, and the opening fails
 * closed — no order is created while the ownership cannot be proven.
 */
@Component
public class FleetClient implements FleetPort {

    public static final String CIRCUIT_NAME = "fleet";

    private static final Logger log = LoggerFactory.getLogger(FleetClient.class);
    private static final Duration DEADLINE = Duration.ofMillis(500);
    private static final long RETRY_BACKOFF_MILLIS = 100;

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public FleetClient(RestClient.Builder restClientBuilder,
                       CircuitBreakerRegistry circuitBreakers,
                       @Value("${erp.fleet.base-url}") String fleetBaseUrl) {
        this(restClientBuilder, circuitBreakers, fleetBaseUrl, configuredDeadlineFactory());
    }

    /** Test entry: a null factory keeps whatever the builder already carries (the mock wire). */
    FleetClient(RestClient.Builder restClientBuilder,
                CircuitBreakerRegistry circuitBreakers,
                String fleetBaseUrl,
                ClientHttpRequestFactory requestFactory) {
        if (requestFactory != null) {
            restClientBuilder.requestFactory(requestFactory);
        }
        this.restClient = restClientBuilder
                .baseUrl(fleetBaseUrl)
                .build();
        this.circuitBreaker = circuitBreakers.circuitBreaker(CIRCUIT_NAME);
    }

    private static ClientHttpRequestFactory configuredDeadlineFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(DEADLINE).build());
        factory.setReadTimeout(DEADLINE);
        return factory;
    }

    @Override
    public Optional<VehicleRegistration> findVehicle(UUID tenantId, UUID vehicleId) {
        try {
            return circuitBreaker.executeSupplier(() -> withOneRetry(vehicleId));
        } catch (CallNotPermittedException openCircuit) {
            throw new FleetUnavailableException("the fleet circuit is open: the verification was not attempted",
                    openCircuit);
        }
    }

    private Optional<VehicleRegistration> withOneRetry(UUID vehicleId) {
        try {
            return send(vehicleId);
        } catch (TransientFleetFailure first) {
            log.warn("Transient fleet failure while verifying vehicle, retrying once: vehicleId={}", vehicleId);
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FleetUnavailableException("the fleet verification retry was interrupted", interrupted);
            }
            try {
                return send(vehicleId);
            } catch (TransientFleetFailure second) {
                throw new FleetUnavailableException(
                        "the fleet did not answer within the deadline after one retry", second.getCause());
            }
        }
    }

    private Optional<VehicleRegistration> send(UUID vehicleId) {
        String token = PrincipalTokenHolder.getToken();
        if (token == null || token.isBlank()) {
            throw new FleetUnavailableException(
                    "the verification call carries the verified principal's token and the request has none");
        }
        try {
            org.springframework.http.ResponseEntity<FleetVehicleResponse> response = restClient.get()
                    .uri("/api/v1/fleet/vehicles/{vehicleId}", vehicleId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Tenant-ID", tenantHeaderValue())
                    .retrieve()
                    .toEntity(FleetVehicleResponse.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(new VehicleRegistration(response.getBody().id(), response.getBody().customerId()));
            }
            return Optional.empty();
        } catch (HttpClientErrorException.NotFound unknownVehicle) {
            // A decisive business answer: the vehicle does not exist in the
            // tenant, so the caller rejects the opening with the vehicle's code.
            return Optional.empty();
        } catch (RestClientResponseException rejection) {
            if (rejection.getStatusCode().is5xxServerError()) {
                throw new TransientFleetFailure("the fleet responded with a server error", rejection);
            }
            failFastOnUnverifiedOwnership(rejection.getStatusCode(), vehicleId);
            throw new FleetUnavailableException(
                    "the fleet rejected the verification call with an answer the opening cannot trust", rejection);
        } catch (ResourceAccessException transportFailure) {
            throw new TransientFleetFailure("the transport to the fleet failed", transportFailure);
        } catch (RestClientException cannotConvert) {
            throw new TransientFleetFailure("the fleet's response could not be converted", cannotConvert);
        }
    }

    private void failFastOnUnverifiedOwnership(HttpStatusCode status, UUID vehicleId) {
        log.warn("The fleet rejected the verification: vehicleId={} status={} — the opening fails closed",
                vehicleId, status.value());
    }

    private String tenantHeaderValue() {
        if (TenantContextHolder.getTenantId() == null) {
            return null;
        }
        return TenantContextHolder.getTenantId().toString();
    }

    /** Internal marker: the failure may be transient, so the retry is allowed. */
    private static final class TransientFleetFailure extends RuntimeException {

        private TransientFleetFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
