package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.CancelWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.RejectQuoteCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.exception.SagaReservedTransitionException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * The compensation half of the saga: a rejected quotation and a
 * cancellation with a LIVE reservation both release the reserved parts
 * exactly once per part, a repeated release returns nothing twice, and a
 * cancellation that touches nothing (no quote yet, or the machine refuses
 * one from EN_EJECUCION) never calls the inventory's release.
 */
class RejectQuoteAndCancelWorkOrderTest extends AbstractWorkOrderIntegrationTest {

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
    private RejectQuoteUseCase rejectQuote;

    @Autowired
    private CancelWorkOrderUseCase cancelOrder;

    @Autowired
    private FakeFleetPort fleet;

    @Autowired
    private FakeInventoryPort inventory;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

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
    void theClientRejectionReleasesEveryReservedPartOnceAndClosesTheOrder() {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID order = quotedOrder(tenant, new WorkOrderItemCommand(part, 2, new BigDecimal("80.00")));
        int claimsBefore = inventory.claimedMovements().size();

        var result = asRequest(tenant, () ->
                rejectQuote.reject(new RejectQuoteCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.RECHAZADA, result.status());
        assertEquals(WorkOrderStatus.COTIZADA, result.origin());
        assertEquals("RECHAZADA", statusOf(order), "the rejection is terminal");
        assertEquals(claimsBefore + 1, inventory.claimedMovements().size(),
                "the release returned the part's reservation");
        assertTrue(inventory.claimed("release", ReservationReleaseService.releaseKey(order, part)).isPresent());
    }

    @Test
    void aCancellationWithALiveReservationReleasesInsteadOfLeakingTheStock() {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID order = quotedOrder(tenant, new WorkOrderItemCommand(part, 3, BigDecimal.ZERO));
        int claimsBefore = inventory.claimedMovements().size();

        var result = asRequest(tenant, () ->
                cancelOrder.cancel(new CancelWorkOrderCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.CANCELADA, result.status());
        assertEquals(WorkOrderStatus.COTIZADA, result.origin());
        assertEquals(claimsBefore + 1, inventory.claimedMovements().size(),
                "the release broke the live reservation");
        assertTrue(inventory.claimed("release", ReservationReleaseService.releaseKey(order, part)).isPresent());
    }

    @Test
    void theReleaseRepeatedWithTheSameKeysDoesNotGiveTheStockBackTwice() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID order = quotedOrder(tenant, new WorkOrderItemCommand(part, 1, BigDecimal.ZERO));

        List<Future<Boolean>> rejections = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            rejections.add(executor.submit((Callable<Boolean>) () ->
                    asRequest(tenant, () -> {
                        try {
                            rejectQuote.reject(new RejectQuoteCommand(tenant, order, UUID.randomUUID()));
                            return Boolean.TRUE;
                        } catch (InvalidWorkOrderTransitionException e) {
                            // a loser against the terminal status is rejected
                            // by the machine; its keys were idempotent anyway.
                            return Boolean.TRUE;
                        }
                    })));
        }
        for (Future<Boolean> rejection : rejections) {
            rejection.get();
        }

        assertEquals("RECHAZADA", statusOf(order));
        List<String> releaseKeys = claimedKeys("release");
        assertEquals(1, releaseKeys.size(), "the repeated release returned the stock ONCE");
        assertEquals(ReservationReleaseService.releaseKey(order, part), releaseKeys.get(0));
    }

    @Test
    void aCancellationOfAnOrderThatNeverQuotesDoesNotTouchTheInventory() {
        UUID tenant = UUID.randomUUID();
        UUID order = underDiagnosis(tenant, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO));
        int claimsBefore = inventory.claimedMovements().size();

        var result = asRequest(tenant, () ->
                cancelOrder.cancel(new CancelWorkOrderCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.CANCELADA, result.status());
        assertEquals(WorkOrderStatus.EN_DIAGNOSTICO, result.origin());
        assertEquals(claimsBefore, inventory.claimedMovements().size(),
                "without a live reservation there is nothing to release");
        assertTrue(inventory.claimedMovements().isEmpty());
    }

    @Test
    void aCancellationFromEnEjecucionIsRefusedByTheMachineAndNeverReleases() {
        UUID tenant = UUID.randomUUID();
        UUID order = underExecution(tenant, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO));

        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> asRequest(tenant, () ->
                        cancelOrder.cancel(new CancelWorkOrderCommand(tenant, order, UUID.randomUUID()))),
                "the consumed stock cannot be given back: the machine refuses the cancellation");

        assertEquals("EN_EJECUCION", statusOf(order));
        assertTrue(claimedKeys("release").isEmpty(),
                "a refused cancellation releases nothing");
    }

    @Test
    void aForeignOrderIsNotFoundByBothDoors() {
        UUID tenant = UUID.randomUUID();
        UUID order = quotedOrder(tenant, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO));

        assertThrows(WorkOrderNotFoundException.class,
                () -> asRequest(UUID.randomUUID(), () ->
                        rejectQuote.reject(new RejectQuoteCommand(UUID.randomUUID(), order, UUID.randomUUID()))));
        assertThrows(WorkOrderNotFoundException.class,
                () -> asRequest(UUID.randomUUID(), () ->
                        cancelOrder.cancel(new CancelWorkOrderCommand(UUID.randomUUID(), order, UUID.randomUUID()))),
                "an order of another tenant does not exist here");
    }

    @Test
    void theGenericTransitionCannotEscapeTheQuotedStatus() {
        UUID tenant = UUID.randomUUID();
        UUID order = quotedOrder(tenant, new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO));

        assertThrows(SagaReservedTransitionException.class,
                () -> asRequest(tenant, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.RECHAZADA, UUID.randomUUID()))),
                "the rejection releases the stock through its own door");
        assertThrows(SagaReservedTransitionException.class,
                () -> asRequest(tenant, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.CANCELADA, UUID.randomUUID()))),
                "the cancellation of a quoted order releases through its own door");
        assertEquals("COTIZADA", statusOf(order));
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

    private UUID withItems(UUID tenant, UUID order, WorkOrderItemCommand... items) {
        runAsRequest(tenant, () -> addItems.addItems(
                new AddItemsToWorkOrderCommand(tenant, order, List.of(items))));
        return order;
    }

    private UUID underDiagnosis(UUID tenant, WorkOrderItemCommand... items) {
        UUID order = openedOrder(tenant, UUID.randomUUID()).orderId();
        if (items.length > 0) {
            withItems(tenant, order, items);
        }
        runAsRequest(tenant, () -> transitionOrder.transition(
                new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));
        return order;
    }

    private UUID quotedOrder(UUID tenant, WorkOrderItemCommand... items) {
        UUID order = underDiagnosis(tenant, items);
        runAsRequest(tenant, () -> quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID())));
        return order;
    }

    private UUID underExecution(UUID tenant, WorkOrderItemCommand... items) {
        UUID order = quotedOrder(tenant, items);
        runAsRequest(tenant, () -> approveOrder.approve(new ApproveQuoteCommand(tenant, order, UUID.randomUUID())));
        return order;
    }

    private List<String> claimedKeys(String operation) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, com.erp.workorder.domain.port.InventoryPort.StockConfirmation> entry
                : inventory.claimedMovements()) {
            if (entry.getKey().startsWith(operation + ":")) {
                keys.add(entry.getKey().substring((operation + ":").length()));
            }
        }
        return keys;
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
