package com.erp.workorder.infrastructure.client;

import com.erp.workorder.application.exception.InventoryUnavailableException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.infrastructure.security.PrincipalTokenHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The saga client's transport guarantees, with the wire observed directly:
 * the principal's token re-sent in the Authorization header, the tenant
 * header copied from the context, and the 500 ms deadline exhausted
 * answered as an availability failure.
 */
class InventoryClientTest {

    private static final String RESERVE_URL = "http://inventory.test/api/v1/inventory/stock/reserve";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private InventoryClient client;

    private final UUID part = UUID.randomUUID();
    private final UUID branch = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final String key = "wo:" + order + ":reserve:" + part;

    private SagaStockOperation reserve;

    @BeforeEach
    void buildTheClient() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new InventoryClient(restClientBuilder, "http://inventory.test", null);
        reserve = new SagaStockOperation(part, branch, 2, order, key);

        PrincipalTokenHolder.setToken("verified.principal.token");
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void withNoResidue() {
        PrincipalTokenHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void thePrincipalTokenTravelsInTheAuthorizationHeader() {
        server.expect(once(), requestTo(RESERVE_URL))
                .andExpect(header("Authorization", "Bearer verified.principal.token"))
                .andExpect(header("X-Tenant-ID", TenantContextHolder.getTenantId().toString()))
                .andRespond(withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + UUID.randomUUID() + "\",\"movementType\":\"RESERVE\",\"quantity\":2}"));

        InventoryStockOutcome outcome = client.reserve(reserve);

        assertEquals("RESERVE", outcome.movementType());
        server.verify();
    }

    @Test
    void theSagaCallRequiresTheVerifiedTokenAndFailsClosedWithoutIt() {
        PrincipalTokenHolder.clear();

        InventoryUnavailableException failure = assertThrows(InventoryUnavailableException.class,
                () -> client.reserve(reserve));

        assertTrue(failure.getMessage().contains("verified principal's token"));
        server.verify();
    }

    @Test
    void anAnswerStackedAfterTheDeadlineExpiresIt() {
        server.expect(once(), requestTo(RESERVE_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        InventoryUnavailableException failure = assertThrows(InventoryUnavailableException.class,
                () -> client.reserve(reserve));

        assertTrue(failure.getMessage().contains("did not answer within the deadline"));
        server.verify();
    }

    @Test
    void aServerErrorResponseIsAnAvailabilityFailure() {
        server.expect(once(), requestTo(RESERVE_URL))
                .andRespond(withServerError());

        assertThrows(InventoryUnavailableException.class, () -> client.reserve(reserve));
        server.verify();
    }

    @Test
    void aBusinessRejectionIsDecisiveAndNotAnAvailabilityFailure() {
        server.expect(once(), requestTo(RESERVE_URL))
                .andRespond(withStatus(CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"STOCK_INSUFFICIENT\"}"));

        StockOperationRejectedException rejection = assertThrows(StockOperationRejectedException.class,
                () -> client.reserve(reserve));

        assertTrue(rejection.getMessage().contains("409"));
        assertTrue(rejection.inventoryCode().contains("STOCK_INSUFFICIENT"));
        server.verify();
    }
}
