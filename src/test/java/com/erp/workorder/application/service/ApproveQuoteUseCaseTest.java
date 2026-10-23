package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.exception.SagaReservedTransitionException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.InventoryPort;
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
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The approval of the stock saga: every reserved part is consumed once,
 * the order lands at EN_EJECUCION only when the inventory committed, and a
 * repeated approval claims the SAME keys so the inventory consumes once.
 */
class ApproveQuoteUseCaseTest extends AbstractWorkOrderIntegrationTest {

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
    void theClientApprovalCommitsEveryReservedPartAndLandsAtEnEjecucion() {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID order = quotedOrder(tenant, branch, new WorkOrderItemCommand(part, 2, new BigDecimal("80.00")));
        int claimsBefore = inventory.claimedMovements().size();

        var result = asRequest(tenant, () ->
                approveOrder.approve(new ApproveQuoteCommand(tenant, order, UUID.randomUUID())));

        assertEquals(WorkOrderStatus.EN_EJECUCION, result.status());
        assertEquals(WorkOrderStatus.COTIZADA, result.origin());
        assertEquals("EN_EJECUCION", statusOf(order), "the order only advances when the inventory committed");

        assertEquals(claimsBefore + 1, inventory.claimedMovements().size(),
                "the commit registered ONE consumption movement");
        Optional<InventoryPort.StockConfirmation> commit =
                inventory.claimed("commit", ApproveQuoteUseCase.commitKey(order, part));
        assertTrue(commit.isPresent(), "the commit claimed its movement");
        assertEquals(2, commit.orElseThrow().quantity(), "the movement consumed the part's quantity");
    }

    @Test
    void aRejectedCommitLeavesTheOrderQuotedWithItsLiveReservation() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID order = quotedOrder(tenant, UUID.randomUUID(),
                new WorkOrderItemCommand(part, 2, new BigDecimal("80.00")));
        int claimsBefore = inventory.claimedMovements().size();
        int historyBefore = count("work_order_status_history", order);
        int itemsBefore = count("work_order_items", order);

        inventory.rejectNext(new StockOperationRejectedException("no reservation for this key", "{}"));

        assertThrows(StockOperationRejectedException.class,
                () -> asRequest(tenant, () ->
                        approveOrder.approve(new ApproveQuoteCommand(tenant, order, UUID.randomUUID()))));

        assertEquals("COTIZADA", statusOf(order), "the order keeps its quoted status");
        assertEquals(historyBefore, count("work_order_status_history", order),
                "no approval history row was written");
        assertEquals(itemsBefore, count("work_order_items", order),
                "the order's relationships are exactly what they were");
        assertEquals(claimsBefore, inventory.claimedMovements().size(),
                "a failed commit claims no consumption");
        assertTrue(inventory.claimed("commit", ApproveQuoteUseCase.commitKey(order, part)).isEmpty(),
                "no consumption got through the rejected commit");
    }

    @Test
    void theRepeatedApprovalConsumesOnceWithoutRepeatingTheConsumption() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID part = UUID.randomUUID();
        UUID order = quotedOrder(tenant, UUID.randomUUID(),
                new WorkOrderItemCommand(part, 3, BigDecimal.ZERO));
        int reserveClaimsBefore = countClaims("reserve");

        List<Future<Boolean>> launches = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            launches.add(executor.submit((Callable<Boolean>) () ->
                    asRequest(tenant, () -> {
                        try {
                            approveOrder.approve(new ApproveQuoteCommand(tenant, order, UUID.randomUUID()));
                            return Boolean.TRUE;
                        } catch (InvalidWorkOrderTransitionException e) {
                            // a loser of an already-approved status is
                            // rejected by the machine; its keys were
                            // idempotent anyway.
                            return Boolean.TRUE;
                        }
                    })));
        }
        for (Future<Boolean> launch : launches) {
            launch.get();
        }

        assertEquals("EN_EJECUCION", statusOf(order));
        List<String> commitKeys = claimedKeys("commit");
        assertEquals(1, commitKeys.size(), "the repeated approval consumed ONCE");
        assertEquals(ApproveQuoteUseCase.commitKey(order, part), commitKeys.get(0));
        assertEquals(countClaims("reserve"), reserveClaimsBefore,
                "the approval consumes: it does not reserve again");
    }

    @Test
    void anApprovalOfAForeignOrderIsNotFound() {
        UUID tenant = UUID.randomUUID();
        UUID order = quotedOrder(tenant, UUID.randomUUID());

        assertThrows(WorkOrderNotFoundException.class,
                () -> asRequest(UUID.randomUUID(), () ->
                        approveOrder.approve(new ApproveQuoteCommand(UUID.randomUUID(), order, UUID.randomUUID()))),
                "an order of another tenant does not exist here");
        assertFalse(inventory.claimed("commit", ApproveQuoteUseCase.commitKey(order, UUID.randomUUID())).isPresent());
    }

    @Test
    void theGenericTransitionCannotReachEnEjecucionFromAQuotedOrder() {
        UUID tenant = UUID.randomUUID();
        UUID order = quotedOrder(tenant, UUID.randomUUID());

        assertThrows(SagaReservedTransitionException.class,
                () -> asRequest(tenant, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.EN_EJECUCION, UUID.randomUUID()))),
                "the approval is the only door that consumes the reservation");
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

    private UUID quotedOrder(UUID tenant, UUID branch, WorkOrderItemCommand... items) {
        UUID order = openedOrder(tenant, branch).orderId();
        if (items.length > 0) {
            runAsRequest(tenant, () -> addItems.addItems(
                    new AddItemsToWorkOrderCommand(tenant, order, List.of(items))));
        }
        runAsRequest(tenant, () -> transitionOrder.transition(
                new TransitionWorkOrderCommand(tenant, order, WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID())));
        runAsRequest(tenant, () -> quoteOrder.quote(new QuoteWorkOrderCommand(tenant, order, UUID.randomUUID())));
        return order;
    }

    private List<String> claimedKeys(String operation) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, InventoryPort.StockConfirmation> entry : inventory.claimedMovements()) {
            if (entry.getKey().startsWith(operation + ":")) {
                keys.add(entry.getKey().substring((operation + ":").length()));
            }
        }
        return keys;
    }

    private int countClaims(String operation) {
        return claimedKeys(operation).size();
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
