package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.InventoryUnavailableException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The synchronous saga call against the inventory's stock, with the
 * guarantees the design fixes for internal calls:
 *
 * <ul>
 *   <li><b>Deadline of 500 ms</b> in connection and reading (the planner's
 *   hard limit): a slow inventory is an availability failure, not a wait
 *   the workshop pays for.</li>
 *   <li>The <b>verified principal's token</b> re-sent in the Authorization
 *   header: the path carries ONE principal, with no service credentials and
 *   no anonymous internal call.</li>
 * </ul>
 *
 * <p>A business rejection (4xx, insufficient stock) is NOT an availability
 * failure: it is a decisive answer from the inventory and the transaction
 * of the use case rolls back with it.
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);
    private static final Duration DEADLINE = Duration.ofMillis(500);

    public static final String RESERVE_PATH = "/api/v1/inventory/stock/reserve";
    public static final String COMMIT_PATH = "/api/v1/inventory/stock/commit";
    public static final String RELEASE_PATH = "/api/v1/inventory/stock/release";

    private final RestClient restClient;

    @Autowired
    public InventoryClient(RestClient.Builder restClientBuilder,
                           @Value("${erp.inventory.base-url}") String inventoryBaseUrl) {
        this(restClientBuilder, inventoryBaseUrl, deadlineFactory());
    }

    /** Test entry: a null factory keeps whatever the builder already carries (the mock wire). */
    InventoryClient(RestClient.Builder restClientBuilder,
                    String inventoryBaseUrl,
                    ClientHttpRequestFactory requestFactory) {
        if (requestFactory != null) {
            restClientBuilder.requestFactory(requestFactory);
        }
        this.restClient = restClientBuilder
                .baseUrl(inventoryBaseUrl)
                .build();
    }

    private static ClientHttpRequestFactory deadlineFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(DEADLINE).build());
        factory.setReadTimeout(DEADLINE);
        return factory;
    }

    public InventoryStockOutcome reserve(SagaStockOperation operation) {
        return send(RESERVE_PATH, operation);
    }

    public InventoryStockOutcome commit(SagaStockOperation operation) {
        return send(COMMIT_PATH, operation);
    }

    public InventoryStockOutcome release(SagaStockOperation operation) {
        return send(RELEASE_PATH, operation);
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
                log.warn("Transient inventory failure: op={} status={}", path, rejection.getStatusCode().value());
                throw new InventoryUnavailableException(
                        "the inventory responded with a server error", rejection);
            }
            throw new StockOperationRejectedException(
                    "the inventory rejected the stock operation with HTTP status "
                            + rejection.getStatusCode().value(),
                    rejection.getResponseBodyAsString());
        } catch (ResourceAccessException transportFailure) {
            if (transportFailure.getCause() instanceof java.net.SocketTimeoutException) {
                log.warn("The inventory did not answer within the 500 ms deadline: op={}", path);
            } else {
                log.warn("The transport to the inventory failed: op={}", path);
            }
            throw new InventoryUnavailableException(
                    "the inventory did not answer within the deadline", transportFailure);
        } catch (RestClientException cannotConvert) {
            log.warn("The inventory's response could not be converted: op={}", path);
            throw new InventoryUnavailableException(
                    "the inventory's response could not be converted", cannotConvert);
        }
    }

    private String tenantHeaderValue() {
        if (TenantContextHolder.getTenantId() == null) {
            return null;
        }
        return TenantContextHolder.getTenantId().toString();
    }
}
