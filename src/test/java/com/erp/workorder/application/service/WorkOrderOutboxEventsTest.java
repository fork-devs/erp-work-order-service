package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.CancelWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two event families of the order travel in the outbox TOGETHER with
 * their status change: the delivery writes its WorkOrderCompleted and the
 * cancellation writes its WorkOrderCancelled — with the tenant's and the
 * order's identifiers in the payload, and the event_id as the consumer's
 * identity. Whichever door cancels, the event leaves.
 */
class WorkOrderOutboxEventsTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private TransitionWorkOrderUseCase transitionOrder;

    @Autowired
    private CancelWorkOrderUseCase cancelOrder;

    @Autowired
    private FakeFleetPort fleet;

    @BeforeEach
    @AfterEach
    void clean() throws SQLException {
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
    void theDeliveryLeavesItsCompletedEventNextToTheHistoryRow() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID order = deliveredOrder(tenant, UUID.randomUUID());

        assertEquals(1, eventCount(order, "WorkOrderCompleted"),
                "exactly one completed event per real delivery");
        assertEquals(tenant.toString(), payloadFieldOf(order, "WorkOrderCompleted", "tenantId"),
                "the payload carries the tenant");
        assertEquals(order.toString(), payloadFieldOf(order, "WorkOrderCompleted", "orderId"),
                "the payload carries the order");
        assertEquals("ENTREGADA", payloadFieldOf(order, "WorkOrderCompleted", "status"),
                "the payload carries the status reached");
        assertTrue(eventIdOf(order).isPresent(), "the event carries its consumer identity");
    }

    @Test
    void theCancellationThroughTheGenericDoorLeavesItsCancelledEvent() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID order = openedOrder(tenant, UUID.randomUUID()).orderId();

        asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.CANCELADA, UUID.randomUUID())));

        assertEquals(1, eventCount(order, "WorkOrderCancelled"));
        assertEquals(tenant.toString(), payloadFieldOf(order, "WorkOrderCancelled", "tenantId"));
        assertEquals(order.toString(), payloadFieldOf(order, "WorkOrderCancelled", "orderId"));
        assertEquals("CANCELADA", payloadFieldOf(order, "WorkOrderCancelled", "status"));
    }

    @Test
    void theCancellationThroughItsOwnDoorLeavesTheSameEventFamily() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID cancellable = openedOrder(tenant, UUID.randomUUID()).orderId();

        asRequest(tenant, () -> cancelOrder.cancel(new CancelWorkOrderCommand(
                tenant, cancellable, UUID.randomUUID())));

        assertEquals(1, eventCount(cancellable, "WorkOrderCancelled"),
                "whichever door cancels, the event leaves");
        assertEquals(tenant.toString(), payloadFieldOf(cancellable, "WorkOrderCancelled", "tenantId"));
    }

    @Test
    void anOrderThatNeverReachedItsDestinationKeptNoEventBehind() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID order = openedOrder(tenant, UUID.randomUUID()).orderId();

        // The cancellation refused by the machine (an order at RECEPCIONADA
        // CANNOT reach ENTREGADA): nothing moves, nothing records.
        asRequest(tenant, () -> org.junit.jupiter.api.Assertions.assertThrows(
                InvalidWorkOrderTransitionException.class, () ->
                        transitionOrder.transition(new TransitionWorkOrderCommand(
                                tenant, order, WorkOrderStatus.ENTREGADA, UUID.randomUUID()))));

        assertFalse(anyEventFor(order), "a refused transition published nothing");
    }

    // -- helpers ----------------------------------------------------------

    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    private void runAsRequest(UUID tenantId, Runnable operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            operation.run();
        }
    }

    private OpenWorkOrderResult openedOrder(UUID tenant, UUID branch) {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        return asRequest(tenant, () -> openOrder.open(new OpenWorkOrderCommand(
                tenant, customer, vehicle, branch, UUID.randomUUID(), null)));
    }

    private UUID deliveredOrder(UUID tenant, UUID branch) {
        UUID order = openedOrder(tenant, branch).orderId();
        runAsRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));
        runAsRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.EN_EJECUCION, UUID.randomUUID())));
        runAsRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.EN_CONTROL_CALIDAD, UUID.randomUUID())));
        runAsRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.LISTA, UUID.randomUUID())));
        runAsRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, order, WorkOrderStatus.ENTREGADA, UUID.randomUUID())));
        return order;
    }

    private int eventCount(UUID orderId, String eventType) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM work_order_outbox WHERE aggregate_id = ? AND event_type = ?")) {
            statement.setObject(1, orderId);
            statement.setString(2, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private String payloadFieldOf(UUID orderId, String eventType, String field) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload->>? FROM work_order_outbox WHERE aggregate_id = ? AND event_type = ?")) {
            statement.setString(1, field);
            statement.setObject(2, orderId);
            statement.setString(3, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private java.util.Optional<UUID> eventIdOf(UUID orderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_id FROM work_order_outbox WHERE aggregate_id = ?")) {
            statement.setObject(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? java.util.Optional.of(UUID.fromString(rows.getString(1)))
                        : java.util.Optional.empty();
            }
        }
    }

    private boolean anyEventFor(UUID orderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM work_order_outbox WHERE aggregate_id = ?")) {
            statement.setObject(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
