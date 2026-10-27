package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.dto.result.TransitionWorkOrderResult;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transition applies what the state machine DECLARES and rejects
 * explicitly what it does not: a rejected transition leaves the database
 * unchanged in NOTHING — same status, same history row count, no closure.
 */
class TransitionWorkOrderUseCaseTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private TransitionWorkOrderUseCase transitionOrder;

    @Autowired
    private QuoteWorkOrderUseCase quoteOrder;

    @Autowired
    private ApproveQuoteUseCase approveOrder;

    @Autowired
    private FakeFleetPort fleet;

    @BeforeEach
    void clean() throws SQLException {
        fleet.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
        }
    }

    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    private OpenWorkOrderResult openedOrder(UUID tenant) {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        return asRequest(tenant, () -> openOrder.open(new OpenWorkOrderCommand(
                tenant, customer, vehicle, UUID.randomUUID(), UUID.randomUUID(), null)));
    }

    private int count(String table, UUID orderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM " + table + " WHERE work_order_id = '" + orderId + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String statusOf(UUID orderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT status FROM work_orders WHERE id = '" + orderId + "'")) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    void theDeclaredTransitionAppliesAndLeavesItsHistoryRow() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = openedOrder(tenant).orderId();

        TransitionWorkOrderResult result = asRequest(tenant, () -> transitionOrder.transition(
                new TransitionWorkOrderCommand(tenant, orderId, WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.EN_DIAGNOSTICO, result.status());
        assertEquals(WorkOrderStatus.RECEPCIONADA, result.origin());
        assertEquals("EN_DIAGNOSTICO", statusOf(orderId));
        assertEquals(2, count("work_order_status_history", orderId), "opening plus this transition");
    }

    @Test
    void aSkippedTransitionIsRejectedAndAltersNothing() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = openedOrder(tenant).orderId();
        int historyBefore = count("work_order_status_history", orderId);

        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> asRequest(tenant, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(tenant, orderId, WorkOrderStatus.COTIZADA, UUID.randomUUID()))),
                "RECEPCIONADA -> COTIZADA skips the diagnosis: the machine does not declare it");

        assertEquals("RECEPCIONADA", statusOf(orderId), "the status did not move");
        assertEquals(historyBefore, count("work_order_status_history", orderId),
                "no history row was written for the rejected transition");
    }

    @Test
    void aDeliveredOrderClosesAndNeverReopens() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = openedOrder(tenant).orderId();
        UUID actor = UUID.randomUUID();

        asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, orderId, WorkOrderStatus.EN_DIAGNOSTICO, actor)));
        // EN_DIAGNOSTICO -> COTIZADA belongs to the stock saga: the generic
        // transition refuses to bypass the reservation.
        asRequest(tenant, () -> quoteOrder.quote(new QuoteWorkOrderCommand(tenant, orderId, actor)));
        // COTIZADA -> EN_EJECUCION belongs to the stock saga: the approval
        // consumes the reserved stock through its own use case.
        asRequest(tenant, () -> approveOrder.approve(new ApproveQuoteCommand(tenant, orderId, actor)));
        asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, orderId, WorkOrderStatus.EN_CONTROL_CALIDAD, actor)));
        asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, orderId, WorkOrderStatus.LISTA, actor)));
        asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                tenant, orderId, WorkOrderStatus.ENTREGADA, actor)));

        assertEquals("ENTREGADA", statusOf(orderId));

        int historyAtDelivery = count("work_order_status_history", orderId);
        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> asRequest(tenant, () -> transitionOrder.transition(new TransitionWorkOrderCommand(
                        tenant, orderId, WorkOrderStatus.EN_EJECUCION, actor))),
                "ENTREGADA is terminal");
        assertEquals(historyAtDelivery, count("work_order_status_history", orderId),
                "the closed order writes nothing more");

        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT closed_at FROM work_orders WHERE id = '" + orderId + "'")) {
            rows.next();
            assertTrue(rows.getTimestamp(1) != null, "the delivery closes the order");
        }
    }
}
