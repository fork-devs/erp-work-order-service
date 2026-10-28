package com.erp.workorder.concurrency;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.service.AddItemsToWorkOrderUseCase;
import com.erp.workorder.application.service.ApproveQuoteUseCase;
import com.erp.workorder.application.service.OpenWorkOrderUseCase;
import com.erp.workorder.application.service.QuoteWorkOrderUseCase;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three rules NO race can break, with REAL threads and a REAL database:
 *
 * <ul>
 *   <li>Two openings of the same branch at the same instant: each takes ITS
 *   number from the sequence; none can repeat the other's.</li>
 *   <li>Two quotes racing for the same order: the saga reserves ONCE.</li>
 *   <li>Two approvals racing for the same order: the inventory consumes
 *   ONCE.</li>
 * </ul>
 *
 * <p>The losers of the race tolerate the machine's refusal: the status
 * guard (the aggregate) or the database guard (the optimistic WHERE) may
 * answer first — whatever answers, the answer is a refusal.
 */
class WorkOrderConcurrencyTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;
    @Autowired
    private AddItemsToWorkOrderUseCase addItems;
    @Autowired
    private QuoteWorkOrderUseCase quoteOrder;
    @Autowired
    private ApproveQuoteUseCase approveOrder;
    @Autowired
    private com.erp.workorder.application.service.TransitionWorkOrderUseCase transitionOrder;

    @Autowired
    private FakeFleetPort fleet;
    @Autowired
    private FakeInventoryPort inventory;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        fleet.reset();
        inventory.reset();
    }

    @AfterEach
    void clean() throws Exception {
        threads.shutdownNow();
        assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
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
    void twoSimultaneousOpeningsOfTheSameBranchTakeDifferentNumbers() throws Exception {
        UUID tenant = UUID.randomUUID();
        List<OpenWorkOrderResult> openings = runInParallel(() -> open(tenant), () -> open(tenant));

        assertEquals(2, openings.size(), "both openings survived the race");
        assertNotEquals(openings.get(0).number(), openings.get(1).number(),
                "the sequence gave each opening its own number");
        assertEquals(2, sequenceAdvancesOfTenant(tenant),
                "both numbers went through the branch's sequence exactly once each");

        fleet.reset();
        inventory.reset();
    }

    @Test
    void twoConcurrentQuotesReserveTheSameStockExactlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID orderId = diagnosableWithItems(tenant);

        List<Outcome> outcomes = runInParallel(
                () -> attempt(() -> asRequest(tenant, () -> quoteOrder.quote(
                        new QuoteWorkOrderCommand(tenant, orderId, UUID.randomUUID())))),
                () -> attempt(() -> asRequest(tenant, () -> quoteOrder.quote(
                        new QuoteWorkOrderCommand(tenant, orderId, UUID.randomUUID())))));

        outcomes.forEach(outcome -> assertTrue(outcome.succeeded || outcome.refused,
                "a race loser is either a winner or a refusal: " + outcome.error));

        assertTrue(outcomes.stream().anyMatch(outcome -> outcome.succeeded),
                "at least one quote carried the order to COTIZADA");
        // The racing attempts may repeat the WIRE call; the claim by SAME key
        // is what creates the reservation, and it happens exactly once — the
        // echo is the first confirmation, not a second reservation.
        assertEquals(1, inventory.claimedMovements().stream()
                        .filter(entry -> entry.getKey().startsWith("reserve:")).count(),
                "one reservation, granted by the key's claim, echoed to the racer");
        assertEquals(2, inventory.claimed("reserve",
                        "wo:" + orderId + ":reserve:" + sparePartOf(orderId)).orElseThrow()
                .quantity(), "the echoed confirmation is the reservation itself");
    }

    @Test
    void twoConcurrentApprovalsConsumeTheReservedStockExactlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID orderId = quotedOrder(tenant);

        List<Outcome> outcomes = runInParallel(
                () -> attempt(() -> asRequest(tenant, () -> approveOrder.approve(
                        new ApproveQuoteCommand(tenant, orderId, UUID.randomUUID())))),
                () -> attempt(() -> asRequest(tenant, () -> approveOrder.approve(
                        new ApproveQuoteCommand(tenant, orderId, UUID.randomUUID())))));

        outcomes.forEach(outcome -> assertTrue(outcome.succeeded || outcome.refused,
                "a race loser is either a winner or a refusal: " + outcome.error));

        assertTrue(outcomes.stream().anyMatch(outcome -> outcome.succeeded),
                "at least one approval carried the order to EN_EJECUCION");
        // ONE reserve (from the quote) and ONE commit: the second approval's
        // claim found the commit already consumed under the SAME idempotency
        // key and came back as its echo, not as a second consumption.
        assertEquals(Set.of("reserve:", "commit:"), inventory.claimedMovements().stream()
                        .map(entry -> entry.getKey().split(":")[0] + ":")
                        .collect(java.util.stream.Collectors.toSet()),
                "the claims are exactly one reserve and one commit");
        // TWO entries exactly: a second commit under ANOTHER key (or another
        // reserve) would be a third entry — the size is the once-ness.
        assertEquals(2, inventory.claimedMovements().size());
    }

    // -- helpers ---------------------------------------------------------

    /** A task raced in parallel; its outcome is a success or an explicit refusal. */
    private record Outcome(boolean succeeded, boolean refused, Exception error) {
    }

    private Outcome attempt(Supplier<Object> operation) {
        try {
            operation.get();
            return new Outcome(true, false, null);
        } catch (Exception loser) {
            String kind = loser.getClass().getSimpleName();
            boolean machineSaidNo = kind.contains("InvalidWorkOrderTransition")
                    || kind.contains("IllegalState");
            if (machineSaidNo) {
                return new Outcome(false, true, loser);
            }
            return new Outcome(false, false, loser);
        }
    }

    /** Runs both tasks with REAL threads and joins them together. */
    private <T> List<T> runInParallel(Callable<T> first, Callable<T> second) throws Exception {
        Future<T> first_ = threads.submit(first);
        Future<T> second_ = threads.submit(second);
        List<T> results = new ArrayList<>();
        try {
            results.add(first_.get(60, TimeUnit.SECONDS));
            results.add(second_.get(60, TimeUnit.SECONDS));
        } finally {
            first_.cancel(true);
            second_.cancel(true);
        }
        return results;
    }

    private OpenWorkOrderResult open(UUID tenant) {
        return asRequest(tenant, () -> openOrder.open(opening(tenant)));
    }

    private OpenWorkOrderCommand opening(UUID tenant) {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);
        return new OpenWorkOrderCommand(tenant, customer, vehicle,
                UUID.randomUUID(), UUID.randomUUID(), null);
    }

    /** open -> EN_DIAGNOSTICO -> items: ready for the quote. */
    private UUID diagnosableWithItems(UUID tenant) {
        UUID orderId = open(tenant).orderId();
        transitionOrderTo(tenant, orderId, WorkOrderStatus.EN_DIAGNOSTICO);
        asRequest(tenant, () -> addItems.addItems(new AddItemsToWorkOrderCommand(
                tenant, orderId, List.of(new WorkOrderItemCommand(
                        UUID.randomUUID(), 2, new BigDecimal("30.00"))))));
        return orderId;
    }

    /** open -> EN_DIAGNOSTICO -> items -> quote: ready for the approval. */
    private UUID quotedOrder(UUID tenant) {
        UUID orderId = diagnosableWithItems(tenant);
        asRequest(tenant, () -> quoteOrder.quote(
                new QuoteWorkOrderCommand(tenant, orderId, UUID.randomUUID())));
        return orderId;
    }

    private void transitionOrderTo(UUID tenant, UUID orderId, WorkOrderStatus destination) {
        asRequest(tenant, () -> {
            transitionOrder.transition(new TransitionWorkOrderCommand(
                    tenant, orderId, destination, UUID.randomUUID()));
            return null;
        });
    }

    private UUID sparePartOf(UUID orderId) {
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
            throw new IllegalStateException("the part could not be read", e);
        }
        throw new AssertionError("the order has no items");
    }

    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    /** The tenant's sequence counter: two openings must advance it to 2. */
    private int sequenceAdvancesOfTenant(UUID tenant) {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_number FROM work_order_sequences WHERE tenant_id = ?")) {
            statement.setObject(1, tenant);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the sequence's advance could not be read", e);
        }
        throw new AssertionError("the branch never created its sequence");
    }
}
