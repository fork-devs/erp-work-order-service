package com.erp.workorder.interfaces.rest.admin;

import com.erp.workorder.infrastructure.persistence.outbox.WorkOrderOutboxRelayStore;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeEventPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dead-letter queue's admin door: SUPERADMIN consults it and
 * reprocesses its events. A reprocessed event leaves the queue and
 * publishes on the next sweep.
 */
@AutoConfigureMockMvc
class WorkOrderOutboxAdminControllerTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeEventPublisherPort publisher;

    @Autowired
    private WorkOrderOutboxRelayStore store;

    @BeforeEach
    void clean() throws SQLException {
        publisher.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_outbox");
        }
    }

    @Test
    void theDeadLetterQueueListsItsEventIdsForTheSuperadmin() throws Exception {
        UUID deadOne = deadLettered();
        UUID deadTwo = deadLettered();

        mockMvc.perform(get("/api/v1/work-orders/admin/outbox")
                        .with(user("superadmin").roles("SUPERADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventId").isNotEmpty())
                .andExpect(jsonPath("$[0].eventType").value("WorkOrderCompleted"))
                .andExpect(jsonPath("$[0].tenantId").isNotEmpty())
                .andExpect(jsonPath("$[0].orderId").isNotEmpty());

        // The listed identities are EXACTLY the poisoned ones.
        mockMvc.perform(get("/api/v1/work-orders/admin/outbox")
                        .with(user("superadmin").roles("SUPERADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventId").value(
                        org.hamcrest.Matchers.hasItems(deadOne.toString(), deadTwo.toString())));
    }

    @Test
    void aReprocessedEventLeavesTheQueueAndPublishesOnTheNextSweep() throws Exception {
        UUID eventId = deadLettered();

        mockMvc.perform(post("/api/v1/work-orders/admin/outbox/" + eventId + "/reprocess")
                        .with(user("superadmin").roles("SUPERADMIN")).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        // The context's relay is condition-disabled for determinism; one
        // manual sweep with the REAL store picks the reprocessed event up.
        new com.erp.workorder.infrastructure.messaging.outbox.WorkOrderOutboxRelay(
                store, publisher, 20, 7, 86400).processPending();

        org.junit.jupiter.api.Assertions.assertTrue(publisher.wasPublished(eventId),
                "the reprocessed event went out through the transport");
        mockMvc.perform(get("/api/v1/work-orders/admin/outbox")
                        .with(user("superadmin").roles("SUPERADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reprocessingAnUnknownEventIdAnswersThatThereIsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/admin/outbox/" + UUID.randomUUID() + "/reprocess")
                        .with(user("superadmin").roles("SUPERADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // -- helpers ----------------------------------------------------------

    private UUID deadLettered() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO work_order_outbox (tenant_id, aggregate_id, event_type, event_id, payload, estado, attempts)"
                             + " VALUES (?, ?, 'WorkOrderCompleted', ?, CAST(? AS jsonb), 'DLQ', 7)")) {
            statement.setObject(1, tenant);
            statement.setObject(2, orderId);
            statement.setObject(3, eventId);
            statement.setString(4, "{\"eventType\":\"WorkOrderCompleted\",\"tenantId\":\"" + tenant
                    + "\",\"orderId\":\"" + orderId + "\"}");
            statement.executeUpdate();
        }
        return eventId;
    }
}
