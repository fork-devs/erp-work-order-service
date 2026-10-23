package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The counter never goes back, so the number of a cancelled order is never
 * reused: a cancelled order followed by a new one keeps the increment
 * (same rule ADR-002 fixed for fiscal documents; finding 6.43 closed in
 * the integration hub and never reopened here).
 */
class CancelledOrderNumberNotReusedTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private TransitionWorkOrderUseCase transition;

    @Autowired
    private QuoteWorkOrderUseCase quoteOrder;

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

    /** <T> T runs the operation as a request with the tenant context of the principal. */
    private <T> T asRequest(UUID tenantId, java.util.function.Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    private UUID orderIdOf(OpenWorkOrderResult result) {
        return result.orderId();
    }

    @Test
    void aCancelledOrderFollowedByANewOneKeepsTheIncrement() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);

        OpenWorkOrderCommand open = new OpenWorkOrderCommand(
                tenant, customer, vehicle, UUID.randomUUID(), UUID.randomUUID(), null);

        OpenWorkOrderResult cancelled = asRequest(tenant, () -> openOrder.open(open));
        // RECEPCIONADA -> EN_DIAGNOSTICO -> COTIZADA -> CANCELADA
        asRequest(tenant, () -> transition.transition(new TransitionWorkOrderCommand(
                tenant, cancelled.orderId(), WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));
        // EN_DIAGNOSTICO -> COTIZADA belongs to the stock saga: it runs
        // through the quote use case, never through the generic transition.
        asRequest(tenant, () -> quoteOrder.quote(new QuoteWorkOrderCommand(
                tenant, cancelled.orderId(), UUID.randomUUID())));
        asRequest(tenant, () -> transition.transition(new TransitionWorkOrderCommand(
                tenant, cancelled.orderId(), WorkOrderStatus.CANCELADA, UUID.randomUUID())));

        OpenWorkOrderResult next = asRequest(tenant, () -> openOrder.open(open));

        assertEquals("WO-000001", cancelled.number(), "the counter started fresh");
        assertEquals("WO-000002", next.number(),
                "the cancelled order's number is NOT handed back: the counter never goes back");
    }

    @Test
    void theCounterRowSurvivesAnEmptyTenant() throws SQLException {
        // A tenant whose only order was cancelled: one row, counter at 1, only that.
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        OpenWorkOrderCommand open = new OpenWorkOrderCommand(
                tenant, customer, vehicle, UUID.randomUUID(), UUID.randomUUID(), null);

        OpenWorkOrderResult result = asRequest(tenant, () -> openOrder.open(open));
        asRequest(tenant, () -> transition.transition(new TransitionWorkOrderCommand(
                tenant, orderIdOf(result), WorkOrderStatus.CANCELADA, UUID.randomUUID())));

        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*), max(last_number) FROM work_order_sequences WHERE tenant_id = '" + tenant + "'")) {
            rows.next();
            assertEquals(1, rows.getInt(1), "one counter row per tenant");
            assertEquals(1, rows.getInt(2), "the counter keeps its value");
        }
    }
}
