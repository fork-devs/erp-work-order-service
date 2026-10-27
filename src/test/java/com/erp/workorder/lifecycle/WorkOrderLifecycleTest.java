package com.erp.workorder.lifecycle;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.CancelWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.RejectQuoteCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.dto.result.QuoteWorkOrderResult;
import com.erp.workorder.application.dto.result.TransitionWorkOrderResult;
import com.erp.workorder.application.service.AddItemsToWorkOrderUseCase;
import com.erp.workorder.application.service.ApproveQuoteUseCase;
import com.erp.workorder.application.service.CancelWorkOrderUseCase;
import com.erp.workorder.application.service.OpenWorkOrderUseCase;
import com.erp.workorder.application.service.QuoteWorkOrderUseCase;
import com.erp.workorder.application.service.RejectQuoteUseCase;
import com.erp.workorder.application.service.TransitionWorkOrderUseCase;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import com.erp.workorder.support.FakeInventoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workshop's DAY, end to end: the vehicle enters, the mechanic
 * diagnoses, the parts are quoted against the inventory's RESERVE, the
 * customer approves and the vehicle is delivered — with the branch of
 * rejection and the branch of cancellation, where the reservations are
 * released and nothing is consumed. The events leave through the outbox in
 * every terminal state.
 */
class WorkOrderLifecycleTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;
    @Autowired
    private AddItemsToWorkOrderUseCase addItems;
    @Autowired
    private TransitionWorkOrderUseCase transitionOrder;
    @Autowired
    private QuoteWorkOrderUseCase quoteOrder;
    @Autowired
    private ApproveQuoteUseCase approveOrder;
    @Autowired
    private RejectQuoteUseCase rejectOrder;
    @Autowired
    private CancelWorkOrderUseCase cancelOrder;

    @Autowired
    private FakeFleetPort fleet;
    @Autowired
    private FakeInventoryPort inventory;

    @BeforeEach
    void startClean() {
        fleet.reset();
        inventory.reset();
    }

    @AfterEach
    void clean() throws SQLException {
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

    @Test
    void theWholeCycleFromReceptionToDeliveryConsumesTheReservedStock() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = quotedOrder(tenant).orderId();

        // The approval consumes EXACTLY what was reserved.
        assertEquals(1, inventory.reserveRequests().size(), "one reserve, once");
        var approved = asRequest(tenant, () ->
                approveOrder.approve(new ApproveQuoteCommand(tenant, orderId, UUID.randomUUID())));
        assertEquals("EN_EJECUCION", approved.status().name());
        assertFalse(inventory.claimed("commit", commitKey(orderId)).isEmpty(),
                "the commit was claimed through the approval door");

        // The machine's own steps carry the vehicle forward: control de
        // calidad, lista, entrega.
        TransitionWorkOrderResult atQuality = asRequest(tenant, () ->
                transitionOrder.transition(transition(tenant, orderId, WorkOrderStatus.EN_CONTROL_CALIDAD)));
        TransitionWorkOrderResult listed = asRequest(tenant, () ->
                transitionOrder.transition(transition(tenant, orderId, WorkOrderStatus.LISTA)));
        TransitionWorkOrderResult delivered = asRequest(tenant, () ->
                transitionOrder.transition(transition(tenant, orderId, WorkOrderStatus.ENTREGADA)));

        assertEquals("EN_CONTROL_CALIDAD", atQuality.status().name());
        assertEquals("LISTA", listed.status().name());
        assertEquals("ENTREGADA", delivered.status().name());

        // The terminal state's event left through the outbox.
        assertEquals(1, eventCount(orderId, "WorkOrderCompleted"),
                "the delivery published the order's completion once");
        assertEquals(tenant.toString(), payloadFieldOf(orderId, "WorkOrderCompleted", "tenantId"));
        assertEquals("ENTREGADA", payloadFieldOf(orderId, "WorkOrderCompleted", "status"));
        assertFalse(anyEventOfKind(orderId, "WorkOrderCancelled"), "a delivered order was never cancelled");
    }

    @Test
    void theRejectionBranchReleasesEveryReservationAndConsumesNothing() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = quotedOrder(tenant).orderId();

        var rejected = asRequest(tenant, () -> rejectOrder.reject(rejection(tenant, orderId)));
        assertEquals("RECHAZADA", rejected.status().name());

        // Nothing consumed, everything released.
        assertEquals(1, inventory.reserveRequests().size(), "the rejection does not reserve more");
        assertEquals(Optional.empty(), inventory.claimed("commit", commitKey(orderId)),
                "nothing was consumed through the rejection branch");
        assertTrue(inventory.claimed("release", releaseKey(orderId)).isPresent(),
                "the reservation is released");

        // The rejected order recorded NO completion event (the table's CHECK
        // admits only completed and cancelled, and nothing was emitted).
        assertFalse(anyEventOfKind(orderId, "WorkOrderCompleted"),
                "a rejected order did not complete anything");
    }

    @Test
    void theCancellationBranchReleasesTheReservationsAndRecordsTheEvent() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = quotedOrder(tenant).orderId();

        asRequest(tenant, () -> cancelOrder.cancel(cancellation(tenant, orderId)));

        // The claim map: ONE reserve, ONE release, NO commit.
        assertEquals(2, inventory.claimedMovements().size());
        assertEquals(Optional.empty(), inventory.claimed("commit", commitKey(orderId)),
                "nothing was consumed through the cancellation branch");
        assertTrue(inventory.claimed("release", releaseKey(orderId)).isPresent(),
                "the reservation is released");

        // The cancellation event left through the outbox.
        assertEquals(1, eventCount(orderId, "WorkOrderCancelled"), "one cancelled event");
        assertEquals("CANCELADA", payloadFieldOf(orderId, "WorkOrderCancelled", "status"));
        assertFalse(anyEventOfKind(orderId, "WorkOrderCompleted"), "cancelled is not completed");
    }

    // -- the scenario helpers --------------------------------------------

    /** open -> diagnose -> items -> quote: the order a customer decides about. */
    private QuoteWorkOrderResult quotedOrder(UUID tenant) {
        UUID orderId = openedOrder(tenant);
        asRequest(tenant, () -> transitionOrder.transition(transition(
                tenant, orderId, WorkOrderStatus.EN_DIAGNOSTICO)));
        asRequest(tenant, () -> addItems.addItems(new AddItemsToWorkOrderCommand(
                tenant, orderId, items())));
        return asRequest(tenant, () -> quoteOrder.quote(
                new QuoteWorkOrderCommand(tenant, orderId, UUID.randomUUID())));
    }

    private UUID openedOrder(UUID tenant) {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        return asRequest(tenant, () -> openOrder.open(new OpenWorkOrderCommand(
                tenant, customer, vehicle, UUID.randomUUID(), UUID.randomUUID(), null))).orderId();
    }

    private List<WorkOrderItemCommand> items() {
        return List.of(new WorkOrderItemCommand(
                UUID.randomUUID(), 2, new BigDecimal("45.50")));
    }

    private TransitionWorkOrderCommand transition(UUID tenant, UUID orderId, WorkOrderStatus destination) {
        return new TransitionWorkOrderCommand(tenant, orderId, destination, UUID.randomUUID());
    }

    private RejectQuoteCommand rejection(UUID tenant, UUID orderId) {
        return new RejectQuoteCommand(tenant, orderId, UUID.randomUUID());
    }

    private CancelWorkOrderCommand cancellation(UUID tenant, UUID orderId) {
        return new CancelWorkOrderCommand(tenant, orderId, UUID.randomUUID());
    }

    private String commitKey(UUID orderId) {
        return "wo:" + orderId + ":commit:" + sparePartIdOf(orderId);
    }

    private String releaseKey(UUID orderId) {
        return "wo:" + orderId + ":release:" + sparePartIdOf(orderId);
    }

    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    private UUID sparePartIdOf(UUID orderId) {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT spare_part_id FROM work_order_items WHERE work_order_id = ? LIMIT 1")) {
            statement.setObject(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getObject(1, java.util.UUID.class);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the item's part could not be read", e);
        }
        throw new AssertionError("the order has no items: the quote could not have run");
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

    private boolean anyEventOfKind(UUID orderId, String eventType) throws SQLException {
        return eventCount(orderId, eventType) > 0;
    }

    private String payloadFieldOf(UUID orderId, String eventType, String field) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload->>'" + field + "' FROM work_order_outbox"
                     + " WHERE aggregate_id = ? AND event_type = ?")) {
            statement.setObject(1, orderId);
            statement.setString(2, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
