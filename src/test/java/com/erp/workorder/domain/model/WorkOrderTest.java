package com.erp.workorder.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggregate over its lifetime, with no Spring: the history accumulates
 * one entry per transition and is never rewritten, terminals never reopen,
 * and parts are only touched before the quote reserved against them.
 */
class WorkOrderTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID MECHANIC = UUID.randomUUID();

    private WorkOrder fresh() {
        return WorkOrder.opened(
                UUID.randomUUID(),
                new TenantId(TENANT_A),
                WorkOrderNumber.of(1),
                new CustomerRef(UUID.randomUUID()),
                new VehicleRef(UUID.randomUUID()),
                new BranchId(UUID.randomUUID()),
                null,
                Instant.parse("2026-10-22T15:00:00Z"),
                MECHANIC);
    }

    @Test
    void theHistoryAccumulatesOneEntryPerTransition() {
        WorkOrder order = fresh();
        order.transitionTo(WorkOrderStatus.EN_DIAGNOSTICO, MECHANIC, Instant.parse("2026-10-22T15:10:00Z"));
        order.transitionTo(WorkOrderStatus.COTIZADA, MECHANIC, Instant.parse("2026-10-22T15:20:00Z"));

        assertEquals(3, order.history().size(), "opening plus two transitions");
        assertEquals(WorkOrderStatus.COTIZADA, order.status());
        assertEquals(WorkOrderStatus.RECEPCIONADA, order.history().get(1).transition().origin());
    }

    @Test
    void theHistoryIsImmutable() {
        WorkOrder order = fresh();
        List<StatusChange> history = order.history();
        assertThrows(UnsupportedOperationException.class,
                () -> history.add(new StatusChange(null, MECHANIC, Instant.now())));
    }

    @Test
    void aRejectedTransitionLeavesNeitherStatusNorHistory() {
        WorkOrder order = fresh();
        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> order.transitionTo(WorkOrderStatus.COTIZADA, MECHANIC, Instant.now()));
        assertEquals(WorkOrderStatus.RECEPCIONADA, order.status());
        assertEquals(1, order.history().size(), "only the opening entry");
    }

    @Test
    void theDeliveryClosesTheOrderAndItNeverReopens() {
        WorkOrder order = fresh();
        order.transitionTo(WorkOrderStatus.EN_DIAGNOSTICO, MECHANIC, Instant.parse("2026-10-22T15:10:00Z"));
        order.transitionTo(WorkOrderStatus.COTIZADA, MECHANIC, Instant.parse("2026-10-22T15:20:00Z"));
        order.transitionTo(WorkOrderStatus.EN_EJECUCION, MECHANIC, Instant.parse("2026-10-22T15:25:00Z"));
        order.transitionTo(WorkOrderStatus.EN_CONTROL_CALIDAD, MECHANIC, Instant.parse("2026-10-22T15:30:00Z"));
        order.transitionTo(WorkOrderStatus.LISTA, MECHANIC, Instant.parse("2026-10-22T15:35:00Z"));
        order.transitionTo(WorkOrderStatus.ENTREGADA, MECHANIC, Instant.parse("2026-10-22T15:40:00Z"));

        assertTrue(order.closedAt().isPresent(), "a delivery closes the order");
        assertTrue(order.status().isTerminal());
        assertThrows(InvalidWorkOrderTransitionException.class,
                () -> order.transitionTo(WorkOrderStatus.EN_EJECUCION, MECHANIC, Instant.now()));
    }

    @Test
    void partsOnlyBeforeTheQuote() {
        WorkOrder order = fresh();
        order.addItem(new WorkOrderItem(new SparePartRef(UUID.randomUUID()), 2, new BigDecimal("50.00")));

        order.transitionTo(WorkOrderStatus.EN_DIAGNOSTICO, MECHANIC, Instant.now());
        order.addItem(new WorkOrderItem(new SparePartRef(UUID.randomUUID()), 1, BigDecimal.ZERO));

        order.transitionTo(WorkOrderStatus.COTIZADA, MECHANIC, Instant.now());
        assertThrows(IllegalStateException.class,
                () -> order.addItem(new WorkOrderItem(new SparePartRef(UUID.randomUUID()), 1, BigDecimal.ZERO)),
                "the reservation is out: items cannot change");
        assertFalse(order.items().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> order.items().clear(), "the list is unmodifiable");
    }

    @Test
    void aLiveReservationOnlyExistsAtQuoted() {
        WorkOrder order = fresh();
        assertFalse(order.hasLiveReservation());
        order.transitionTo(WorkOrderStatus.EN_DIAGNOSTICO, MECHANIC, Instant.now());
        order.transitionTo(WorkOrderStatus.COTIZADA, MECHANIC, Instant.now());
        assertTrue(order.hasLiveReservation());
        order.transitionTo(WorkOrderStatus.RECHAZADA, MECHANIC, Instant.now());
        assertFalse(order.hasLiveReservation(), "the rejection released the reservation: it no longer lives");
    }
}
