package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.InventoryPort.StockReservation;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quotation of the stock saga: the order lands at COTIZADA only when
 * the inventory accepted EVERY part's reservation, each part travels with
 * its own idempotency key derived from the order, a business rejection
 * leaves the order unchanged, and a repeated quotation claims ONE
 * movement per part without duplicating items.
 */
class QuoteWorkOrderUseCaseTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private AddItemsToWorkOrderUseCase addItems;

    @Autowired
    private TransitionWorkOrderUseCase transitionOrder;

    @Autowired
    private QuoteWorkOrderUseCase quoteOrder;

    @Autowired
    private FakeFleetPort fleet;

    @Autowired
    private FakeInventoryPort inventory;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void clean() throws SQLException {
        fleet.reset();
        inventory.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
        }
    }

    @AfterEach
    void stopTheThreads() {
        executor.shutdownNow();
    }

    @Test
    void anOrderWithPartsLandsAtCotizadaOnlyWhenTheInventoryConfirmsEveryReservation() {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID order = orderUnderDiagnosis(tenant, branch,
                new WorkOrderItemCommand(part, 2, new BigDecimal("80.00")));

        var result = asRequest(tenant, () ->
                quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.COTIZADA, result.status());
        assertEquals(WorkOrderStatus.EN_DIAGNOSTICO, result.origin());
        assertEquals("COTIZADA", statusOf(order), "the order only advances when the inventory confirmed");

        assertEquals(1, inventory.reserveRequests().size(), "one reservation call per registered item");
        StockReservation sent = inventory.reserveRequests().get(0);
        assertEquals(part, sent.sparePartId());
        assertEquals(branch, sent.branchId(), "the reservation carries the ORDER's branch");
        assertEquals(2, sent.quantity());
        assertEquals(order, sent.workOrderId());
        assertEquals(QuoteWorkOrderUseCase.reservationKey(order, part), sent.idempotencyKey(),
                "the key derives from the order AND the part");
        assertEquals(1, inventory.claimedMovements().size());
    }

    @Test
    void aBusinessRejectionOfTheReservationLeavesTheOrderExactlyWhereItWas() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID order = orderUnderDiagnosis(tenant, UUID.randomUUID(),
                new WorkOrderItemCommand(UUID.randomUUID(), 2, new BigDecimal("80.00")));
        int historyBefore = count("work_order_status_history", order);

        inventory.rejectNext(new StockOperationRejectedException("the inventory has no available stock", "{}"));

        assertThrows(StockOperationRejectedException.class,
                () -> asRequest(tenant, () ->
                        quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID()))),
                "the failure is the saga's rejection, not a client error propagation");

        assertEquals("EN_DIAGNOSTICO", statusOf(order), "the order keeps the previous status");
        assertEquals(historyBefore, count("work_order_status_history", order),
                "no quotation history row was written");
        assertTrue(inventory.claimedMovements().isEmpty(),
                "a failed reserve claims no movement in the inventory");
    }

    @Test
    void theRepeatedQuotationClaimsOneMovementPerPartAndDoesNotDuplicateItems() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID firstPart = UUID.randomUUID();
        UUID secondPart = UUID.randomUUID();
        UUID order = orderUnderDiagnosis(tenant, UUID.randomUUID(),
                new WorkOrderItemCommand(firstPart, 1, BigDecimal.ZERO),
                new WorkOrderItemCommand(secondPart, 3, new BigDecimal("45.50")));
        int itemsBefore = count("work_order_items", order);

        // The "same quote repeated" arrives through several threads racing
        // with the SAME keys: the inventory's claim arbitrates and only ONE
        // movement per part survives.
        List<Future<Boolean>> launches = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            launches.add(executor.submit((Callable<Boolean>) () ->
                    asRequest(tenant, () -> {
                        try {
                            quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID()));
                            return Boolean.TRUE;
                        } catch (IllegalStateException e) {
                            // a loser of an already-quoted status may be
                            // rejected by the machine; its claims were
                            // idempotent anyway.
                            return Boolean.TRUE;
                        }
                    })));
        }
        for (Future<Boolean> launch : launches) {
            launch.get();
        }

        assertEquals("COTIZADA", statusOf(order));
        assertEquals(itemsBefore, count("work_order_items", order), "the quotation never changes the items");
        assertEquals(2, inventory.claimedMovements().size(),
                "the same key claims ONE movement: a repeat does not reserve twice");
        Set<String> keysSent = new TreeSet<>();
        for (StockReservation request : inventory.reserveRequests()) {
            keysSent.add(request.idempotencyKey());
        }
        assertEquals(Set.of(
                        QuoteWorkOrderUseCase.reservationKey(order, firstPart),
                        QuoteWorkOrderUseCase.reservationKey(order, secondPart)),
                keysSent,
                "every thread travels with the SAME keys the claim can arbitrate");
    }

    @Test
    void anOrderWithNoItemsQuotesWithoutTouchingTheInventory() {
        UUID tenant = UUID.randomUUID();
        UUID order = orderUnderDiagnosis(tenant, UUID.randomUUID());

        var result = asRequest(tenant, () ->
                quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.COTIZADA, result.status());
        assertTrue(inventory.reserveRequests().isEmpty(),
                "pure labor quotes nothing: no part, no stock call");
    }

    @Test
    void aForeignOrderIsNotFoundAndTheMachineRefusesToRequote() {
        UUID tenant = UUID.randomUUID();
        UUID order = orderUnderDiagnosis(tenant, UUID.randomUUID(),
                new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO));

        assertThrows(WorkOrderNotFoundException.class,
                () -> asRequest(UUID.randomUUID(), () ->
                        quoteOrder.quote(new QuoteWorkOrderCommand(UUID.randomUUID(), order, UUID.randomUUID()))),
                "an order of another tenant does not exist here");

        asRequest(tenant, () ->
                quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID())));
        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> asRequest(tenant, () ->
                        quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID()))),
                "the machine does not quote twice");
        assertEquals(1, inventory.claimedMovements().size(),
                "the second quote made no further claims");
    }

    // -- setup helpers ---------------------------------------------------

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

    private UUID orderUnderDiagnosis(UUID tenant, WorkOrderItemCommand... items) {
        return orderUnderDiagnosis(tenant, UUID.randomUUID(), items);
    }

    private UUID orderUnderDiagnosis(UUID tenant, UUID branch, WorkOrderItemCommand... items) {
        UUID order = openedOrder(tenant, branch).orderId();
        if (items.length > 0) {
            runAsRequest(tenant, () -> addItems.addItems(
                    new AddItemsToWorkOrderCommand(tenant, order, List.of(items))));
        }
        runAsRequest(tenant, () -> transitionOrder.transition(
                new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));
        return order;
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

    private String statusOf(UUID orderId) {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT status FROM work_orders WHERE id = '" + orderId + "'")) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
