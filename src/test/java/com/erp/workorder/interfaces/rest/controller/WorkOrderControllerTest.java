package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import com.erp.workorder.support.FakeInventoryPort;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API's contract: the endpoint's role, the tenant decided by the token
 * (the body NEVER chooses it — spec P0 5.2), and the saga's doors answering
 * through the same authenticated path.
 */
@AutoConfigureMockMvc
class WorkOrderControllerTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeFleetPort fleet;

    @Autowired
    private FakeInventoryPort inventory;

    private UUID tenant;
    private String asesorToken;
    private String mecanicoToken;

    /** The part quoted in this test's orders, so the claims can be asserted. */
    private UUID sparePartId = UUID.randomUUID();

    @BeforeEach
    void prepareTheScenario() throws Exception {
        fleet.reset();
        inventory.reset();
        tenant = UUID.randomUUID();
        asesorToken = TestJwksServer.signToken(tenant, List.of("ASESOR"));
        mecanicoToken = TestJwksServer.signToken(tenant, List.of("MECANICO"));
    }

    @AfterEach
    void clean() throws Exception {
        fleet.reset();
        inventory.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
            statement.execute("DELETE FROM work_order_outbox");
        }
    }

    // -- opening ---------------------------------------------------------

    @Test
    void anAsesorOpensOrdersAndTheTenantComesFromTheToken() throws Exception {
        UUID foreignTenant = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + asesorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openingBody(foreignTenant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.number").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEPCIONADA"))
                .andReturn();

        // The request body tried to slip ANOTHER tenant in; the database keeps
        // no row of it: the caller's token is the only door into the data.
        UUID orderId = readOrderId(result);
        mockMvc.perform(get("/api/v1/work-orders/" + orderId)
                        .header("Authorization", "Bearer " + asesorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis").value("ruido en la correa"));
    }

    @Test
    void aMechanicReadsButOpensNothing() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders").header("Authorization", "Bearer " + mecanicoToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + mecanicoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openingBody(null)))
                .andExpect(status().isForbidden());
    }

    // -- the saga's doors ------------------------------------------------

    @Test
    void theQuoteAppliesThroughItsOwnEndpointAndTheMechanicCannotApprove() throws Exception {
        UUID orderId = openedOrder();
        move(orderId, "EN_DIAGNOSTICO");
        addItems(orderId);

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/quote")
                        .header("Authorization", "Bearer " + asesorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("EN_DIAGNOSTICO"))
                .andExpect(jsonPath("$.status").value("COTIZADA"));

        // The consumer role cannot decide the workshop's money: approving is
        // a WRITER's decision.
        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/approve")
                        .header("Authorization", "Bearer " + mecanicoToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/approve")
                        .header("Authorization", "Bearer " + asesorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("COTIZADA"))
                .andExpect(jsonPath("$.status").value("EN_EJECUCION"));
    }

    @Test
    void theRejectionDoorAnswersThroughTheApiAndLeavesTheReservationsReleased() throws Exception {
        UUID orderId = openedOrder();
        move(orderId, "EN_DIAGNOSTICO");
        addItems(orderId);
        move(orderId, "COTIZADA", "/quote");

        // The quote reserved ONE item exactly once.
        assertEquals(1, inventory.reserveRequests().size());

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/reject")
                        .header("Authorization", "Bearer " + asesorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"));

        // The rejection released; it did NOT try to reserve again.
        assertEquals(1, inventory.reserveRequests().size());
        // The claims on the fake tell the whole story: ONE reserve (during
        // the quote) and ONE release (during the rejection) — and NO commit.
        assertEquals(2, inventory.claimedMovements().size());
        assertTrue(inventory.claimed("commit",
                        "wo:" + orderId + ":commit:" + sparePartId).isEmpty(),
                "nothing was consumed through the rejection door");
    }

    // -- helpers ---------------------------------------------------------

    private String openingBody(UUID injectedForeignTenant) {
        String foreignTenant = injectedForeignTenant == null ? UUID.randomUUID().toString()
                : injectedForeignTenant.toString();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        return "{" +
                "\"tenantId\":\"" + foreignTenant + "\"," +
                "\"customerId\":\"" + customer + "\"," +
                "\"vehicleId\":\"" + vehicle + "\"," +
                "\"branchId\":\"" + UUID.randomUUID() + "\"," +
                "\"mechanicId\":\"" + UUID.randomUUID() + "\"," +
                "\"diagnosis\":\"ruido en la correa\"" +
                "}";
    }

    private UUID openedOrder() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + asesorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openingBody(null)))
                .andExpect(status().isCreated())
                .andReturn();
        return readOrderId(result);
    }

    private void move(UUID orderId, String destination) throws Exception {
        move(orderId, destination, "/transition");
    }

    private void move(UUID orderId, String destination, String door) throws Exception {
        if (door.equals("/transition")) {
            mockMvc.perform(post("/api/v1/work-orders/" + orderId + door)
                            .header("Authorization", "Bearer " + asesorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"destination\":\"" + destination + "\"}"))
                    .andExpect(status().isOk());
        } else {
            mockMvc.perform(post("/api/v1/work-orders/" + orderId + door)
                            .header("Authorization", "Bearer " + asesorToken))
                    .andExpect(status().isOk());
        }
    }

    private void addItems(UUID orderId) throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/items")
                        .header("Authorization", "Bearer " + asesorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"items\":[" +
                                "{\"sparePartId\":\"" + UUID.randomUUID() + "\"," +
                                "\"quantity\":2,\"laborPrice\":45.50}" +
                                "]}"))
                .andExpect(status().isCreated());
    }

    private UUID readOrderId(MvcResult result) throws java.io.UnsupportedEncodingException {
        // The response body holds the id as text; parsing keeps the test honest.
        String body = result.getResponse().getContentAsString();
        int from = body.indexOf("\"orderId\":\"") + "\"orderId\":\"".length();
        return UUID.fromString(body.substring(from, from + 36));
    }
}
