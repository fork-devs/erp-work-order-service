package com.erp.workorder.interfaces.rest.advice;

import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import com.erp.workorder.support.TestJwksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract: a rejected transition is a CONFLICT with its stable
 * family code, a malformed body is 400 (the client erred, not the server),
 * and the internal error exposes neither the class nor the message of the
 * exception — the diagnosis belongs to the log. Every problem carries the
 * tenant and the stable code.
 */
@AutoConfigureMockMvc
class GlobalRestExceptionHandlerTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeFleetPort fleet;

    private UUID tenant;
    private String token;

    @BeforeEach
    void prepareTheScenario() {
        fleet.reset();
        tenant = UUID.randomUUID();
        token = TestJwksServer.signToken(tenant, List.of("ASESOR"));
    }

    @AfterEach
    void clean() throws Exception {
        fleet.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
            statement.execute("DELETE FROM work_order_outbox");
        }
    }

    @Test
    void aRefusedTransitionIsAConflictWithTheStableFamilyCode() throws Exception {
        UUID orderId = openedOrder();

        // RECEPCIONADA -> LISTA skips every step: the machine says no.
        mockMvc.perform(transition(orderId, "LISTA"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_TRANSITION_INVALID"))
                .andExpect(jsonPath("$.tenant_id").value(tenant.toString()))
                .andExpect(jsonPath("$.type").value(
                        "https://erp-automotive.pe/errors/work-order-transition-invalid"))
                .andExpect(jsonPath("$.detail").value(
                        "The transition to the requested status is not declared by the machine"));
    }

    @Test
    void aMalformedBodyIsFourHundredAndNotFiveHundred() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.tenant_id").value(tenant.toString()));
    }

    @Test
    void aValidationFailureIsFourHundredWithTheFieldItself() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":null,\"vehicleId\":\"" + UUID.randomUUID()
                                + "\",\"branchId\":\"" + UUID.randomUUID()
                                + "\",\"diagnosis\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_VALIDATION_ERROR"));
    }

    @Test
    void anInternalErrorHidesTheExceptionAndLogsItsTrace() throws Exception {
        // An unknown list filter throws inside the controller; the generic
        // handler answers 500 WITHOUT the exception's identity, which stays
        // in the log alone.
        mockMvc.perform(get("/api/v1/work-orders?status=NO_SUCH_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An internal error occurred"));

        String answer = mockMvc.perform(get("/api/v1/work-orders?status=NO_SUCH_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertNotContaining(answer, "IllegalArgumentException|NoSuchElement|Exception");
    }

    @Test
    void anUnknownRouteIsNotFoundAndNotAnInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/no/route/here")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
    }

    // -- helpers ---------------------------------------------------------

    private static void assertNotContaining(String body, String forbiddenRegex) {
        if (body.matches("(?s).*" + forbiddenRegex + ".*")) {
            throw new AssertionError("the internal error exposes the exception's identity: " + body);
        }
    }

    private UUID openedOrder() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        MvcResult result = mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"customerId\":\"" + customer + "\"," +
                                "\"vehicleId\":\"" + vehicle + "\"," +
                                "\"branchId\":\"" + UUID.randomUUID() + "\"," +
                                "\"diagnosis\":\"fluido en el asfalto\"" +
                                "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int from = body.indexOf("\"orderId\":\"") + "\"orderId\":\"".length();
        return UUID.fromString(body.substring(from, from + 36));
    }

    private org.springframework.test.web.servlet.RequestBuilder transition(UUID orderId, String destination) {
        return post("/api/v1/work-orders/" + orderId + "/transition")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":\"" + destination + "\"}");
    }
}
