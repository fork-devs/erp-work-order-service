package com.erp.workorder.application.service;

import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderOutboxPort;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write side of the Transactional Outbox: the event row joins the
 * transaction the USE CASE opens — it never opens its own. Proven in the
 * two directions: a status change commits its event, and a failure inside
 * the caller's transaction leaves NOTHING (no event, no status, no
 * history). The consumer's deduplication identity is honored too: the
 * same event_id never inserts twice.
 */
class WorkOrderOutboxWriteTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private UnitOfWork unitOfWork;

    @Autowired
    private WorkOrderOutboxPort outbox;

    private final UUID tenant = UUID.randomUUID();

    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    @Test
    void anEventRecordedWithItsOwnerCommitsOnlyWithItsTransaction() throws SQLException {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        asRequest(tenant, () -> unitOfWork.callInTransaction(() -> {
            outbox.record(new WorkOrderOutboxPort.WorkOrderOutboxEvent(
                    "WorkOrderCompleted", eventId, tenant, orderId,
                    "{\"eventType\":\"WorkOrderCompleted\",\"tenantId\":\"" + tenant
                            + "\",\"orderId\":\"" + orderId + "\"}"));
            return null;
        }));

        assertTrue(eventRowExists(eventId),
                "the committed transaction carries its event row");
    }

    @Test
    void aFailureInsideTheCallerTransactionCreditsNoEventAnywhere() throws SQLException {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        asRequest(tenant, () -> assertThrows(IllegalStateException.class, () ->
                unitOfWork.callInTransaction(() -> {
                    outbox.record(new WorkOrderOutboxPort.WorkOrderOutboxEvent(
                            "WorkOrderCancelled", eventId, tenant, orderId, "{}"));
                    throw new IllegalStateException("the business write after the event failed");
                })));

        assertTrue(!eventRowExists(eventId),
                "the rolled-back transaction left its event row out: the bus would have "
                        + "received an event the database never confirmed");
    }

    @Test
    void theReinvocationNeverReinsertsTheSameEventId() throws SQLException {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        asRequest(tenant, () -> unitOfWork.callInTransaction(() -> {
            outbox.record(new WorkOrderOutboxPort.WorkOrderOutboxEvent(
                    "WorkOrderCompleted", eventId, tenant, orderId,
                    "{\"eventType\":\"WorkOrderCompleted\",\"tenantId\":\"" + tenant
                            + "\",\"orderId\":\"" + orderId + "\"}"));
            return null;
        }));
        // A second invocation with the SAME identity: nothing new, and the
        // transaction survives (no constraint violation riding on it).
        asRequest(tenant, () -> unitOfWork.callInTransaction(() -> {
            outbox.record(new WorkOrderOutboxPort.WorkOrderOutboxEvent(
                    "WorkOrderCompleted", eventId, tenant, orderId,
                    "{\"different\":\"second attempt\"}"));
            return null;
        }));

        assertEquals(1, countRows("SELECT count(*) FROM work_order_outbox WHERE event_id = '" + eventId + "'"),
                "the same event_id is not reinserted");
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload->>'orderId' FROM work_order_outbox WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertEquals(orderId.toString(), rows.getString(1),
                        "the FIRST invocation's payload stays: the claim keeps the first write");
            }
        }
    }

    @Test
    void thePoisonedEventKillsTheTransactionInsteadOfPublishing() throws SQLException {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        asRequest(tenant, () -> assertThrows(Exception.class, () ->
                unitOfWork.callInTransaction(() -> {
                    // An event the table does not admit: the CHECK rejects it
                    // and the WHOLE transaction — nothing half-published.
                    outbox.record(new WorkOrderOutboxPort.WorkOrderOutboxEvent(
                            "WorkOrderExploded", eventId, tenant, orderId, "{}"));
                    return null;
                })));

        assertEquals(0, countRows("SELECT count(*) FROM work_order_outbox WHERE event_id = '" + eventId + "'"));
    }

    // -- helpers ----------------------------------------------------------

    private boolean eventRowExists(UUID eventId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM work_order_outbox WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private int countRows(String sql) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
