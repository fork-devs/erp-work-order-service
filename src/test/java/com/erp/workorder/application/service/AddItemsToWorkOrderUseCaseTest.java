package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of the order are LOGICAL references: a spare part the inventory
 * service does not know is registered anyway (spec), and the branch of the
 * reservation is the one the order carries. The guarantee of existence and
 * stock arrives at the quote, not at the registration.
 */
class AddItemsToWorkOrderUseCaseTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private AddItemsToWorkOrderUseCase addItems;

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
        return asRequest(tenant, () -> openOrder.open(new OpenWorkOrderCommand(
                tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null)));
    }

    private AddItemsToWorkOrderCommand withItems(UUID tenant, UUID orderId, WorkOrderItemCommand... items) {
        return new AddItemsToWorkOrderCommand(tenant, orderId, List.of(items));
    }

    @Test
    void aSparePartTheInventoryDoesNotKnowIsRegisteredAnyway() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID order = orderIdOf(openedOrder(tenant)).orderId();
        UUID unknownPart = UUID.randomUUID();

        List<UUID> registered = asRequest(tenant, () -> addItems.addItems(
                withItems(tenant, order, new WorkOrderItemCommand(unknownPart, 2, new BigDecimal("50.00")))));

        assertEquals(List.of(unknownPart), registered,
                "the registration does not ask the inventory: the reference stays logical");

        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT spare_part_id, quantity, labor_price FROM work_order_items "
                             + "WHERE work_order_id = '" + order + "'")) {
            rows.next();
            assertEquals(unknownPart, rows.getObject(1, java.util.UUID.class));
            assertEquals(2, rows.getInt(2));
            assertEquals(0, rows.getBigDecimal(3).compareTo(new BigDecimal("50.00")));
        }
    }

    @Test
    void twoDifferentPartsAreBothRegistered() {
        UUID tenant = UUID.randomUUID();
        UUID order = orderIdOf(openedOrder(tenant)).orderId();
        UUID firstPart = UUID.randomUUID();
        UUID secondPart = UUID.randomUUID();

        List<UUID> registered = asRequest(tenant, () -> addItems.addItems(withItems(
                tenant, order,
                new WorkOrderItemCommand(firstPart, 1, BigDecimal.ZERO),
                new WorkOrderItemCommand(secondPart, 3, new BigDecimal("199.99")))));

        assertEquals(List.of(firstPart, secondPart), registered);
    }

    @Test
    void anOrderInQuoteStateAcceptsNoMoreItems() {
        UUID tenant = UUID.randomUUID();
        UUID order = orderIdOf(openedOrder(tenant)).orderId();
        asRequest(tenant, () -> addItems.addItems(
                withItems(tenant, order, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO))));

        assertThrows(IllegalStateException.class, () -> asRequest(tenant, () -> addItems.addItems(
                withItems(tenant, order, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO)))),
                "after COTIZADA the stock guarantee was claimed");
    }

    @Test
    void anOrderOfAnotherTenantIsNotFound() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID orderOfA = orderIdOf(openedOrder(tenantA)).orderId();

        assertThrows(com.erp.workorder.application.exception.WorkOrderNotFoundException.class,
                () -> asRequest(tenantB, () -> addItems.addItems(withItems(
                        tenantB, orderOfA,
                        new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO)))),
                "the absence is indistinguishable from a foreign order");
    }
}
