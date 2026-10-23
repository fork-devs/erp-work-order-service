package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.InventoryUnavailableException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The synchronous saga call against the inventory's stock, with the
 * guarantees the design fixes for internal calls:
 *
 * <ul>
 *   <li><b>Deadline of 500 ms</b> in connection and reading.</li>
 *   <li>The <b>verified principal's token</b> re-sent in the Authorization
 *   header: the path carries ONE principal, with no service credentials and
 *   no anonymous internal call.</li>
 *   <li><b>One retry with backoff</b> on TRANSIENT failures (transport,
 *   deadline, 5xx), with the SAME idempotency key: an unknown outcome is
 *   arbitrated by the inventory's claim, never resolved by guessing.</li>
 *   <li><b>Circuit breaker</b> (instance {@code inventory}): when it is
 *   open, the failure is answered FAST with its availability code — never
 *   with fifteen orders hanging on a dead inventory.</li>
 * </ul>
 *
 * <p>A business rejection (4xx, insufficient stock) is NOT transient: it is
 * not retried and its transaction rolls back with
 * {@code StockOperationRejectedException}.
 */
@Component
public class InventoryClient {

    public static final String CIRCUIT_NAME = "inventory";

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);
    private static final Duration DEADLINE = Duration.ofMillis(500);
    private static final long RETRY_BACKOFF_MILLIS = 100;

    public static final String RESERVE_PATH = "/api/v1/inventory/stock/reserve";
    public static final String COMMIT_PATH = "/api/v1/inventory/stock/commit";
    public static final String RELEASE_PATH = "/api/v1/inventory/stock/release";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryClient(RestClient.Builder restClientBuilder,
                           CircuitBreakerRegistry circuitBreakers,
                           @Value("${erp.inventory.base-url}") String inventoryBaseUrl) {
        this(restClientBuilder, circuitBreakers, inventoryBaseUrl, configuredDeadlineFactory());
    }

    /** Test entry: a null factory keeps whatever the builder already carries (the mock wire). */
    InventoryClient(RestClient.Builder restClientBuilder,
                    CircuitBreakerRegistry circuitBreakers,
                    String inventoryBaseUrl,
                    org.springframework.http.client.ClientHttpRequestFactory requestFactory) {
        if (requestFactory != null) {
            restClientBuilder.requestFactory(requestFactory);
        }
        this.restClient = restClientBuilder
                .baseUrl(inventoryBaseUrl)
                .build();
        this.circuitBreaker = circuitBreakers.circuitBreaker(CIRCUIT_NAME);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory configuredDeadlineFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(DEADLINE).build());
        factory.setReadTimeout(DEADLINE);
        return factory;
    }

    public InventoryStockOutcome reserve(SagaStockOperation operation) {
        return execute(RESERVE_PATH, operation);
    }

    public InventoryStockOutcome commit(SagaStockOperation operation) {
        return execute(COMMIT_PATH, operation);
    }

    public InventoryStockOutcome release(SagaStockOperation operation) {
        return execute(RELEASE_PATH, operation);
    }

    private InventoryStockOutcome execute(String path, SagaStockOperation operation) {
        try {
            return circuitBreaker.executeSupplier(() -> withOneRetry(path, operation));
        } catch (CallNotPermittedException openCircuit) {
            throw new InventoryUnavailableException(
                    "the inventory circuit is open: the saga call was not attempted", openCircuit);
        }
    }

    private InventoryStockOutcome withOneRetry(String path, SagaStockOperation operation) {
        try {
            return send(path, operation);
        } catch (TransientInventoryFailure first) {
            // The SAME key travels in the retry: the inventory's claim
            // arbitrates an unknown outcome.
            log.warn("Transient inventory failure, retrying once with the same idempotency key: op={} key={}",
                    path, operation.idempotencyKey());
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InventoryUnavailableException("the saga call retry was interrupted", interrupted);
            }
            try {
                return send(path, operation);
            } catch (TransientInventoryFailure second) {
                throw new InventoryUnavailableException(
                        "the inventory did not answer within the deadline after one retry", second.getCause());
            }
        }
    }

    private InventoryStockOutcome send(String path, SagaStockOperation operation) {
        String token = PrincipalTokenHolder.getToken();
        if (token == null || token.isBlank()) {
            throw new InventoryUnavailableException(
                    "the saga call carries the verified principal's token and the request has none");
        }
        try {
            return restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Tenant-ID", tenantHeaderValue())
                    .body(operation)
                    .retrieve()
                    .body(InventoryStockOutcome.class);
        } catch (RestClientResponseException rejection) {
            if (rejection.getStatusCode().is5xxServerError()) {
                throw new TransientInventoryFailure(
                        "the inventory responded with a server error", rejection);
            }
            throw new StockOperationRejectedException(
                    "the inventory rejected the stock operation with HTTP status " + rejection.getStatusCode().value(),
                    rejection.getResponseBodyAsString());
        } catch (ResourceAccessException transportFailure) {
            throw new TransientInventoryFailure("the transport to the inventory failed", transportFailure);
        } catch (org.springframework.web.client.RestClientException cannotConvert) {
            throw new TransientInventoryFailure(
                    "the inventory's response could not be converted", cannotConvert);
        }
    }

    private String tenantHeaderValue() {
        if (TenantContextHolder.getTenantId() == null) {
            return null;
        }
        return TenantContextHolder.getTenantId().toString();
    }

    /** Internal marker: the failure may be transient, so the retry is allowed. */
    private static final class TransientInventoryFailure extends RuntimeException {

        private TransientInventoryFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
