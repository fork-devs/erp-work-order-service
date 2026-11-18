package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine, with nothing around it: no Spring anywhere, and every
 * spec scenario is a direct call to the domain.
 *
 * <ul>
 *   <li>A status is skipped: RECEIVED -&gt; QUOTED is rejected.</li>
 *   <li>A delivered order reopens: DELIVERED -&gt; IN_PROGRESS is rejected.</li>
 *   <li>The allowed transition applies: RECEIVED -&gt; IN_DIAGNOSIS.</li>
 * </ul>
 */
class WorkOrderStatusTest {

    @Test
    void allowedTransitionApplies() {
        WorkOrderStatus.Transition transition = WorkOrderStatus.RECEIVED.requireTransitionTo(WorkOrderStatus.IN_DIAGNOSIS);

        assertEquals(WorkOrderStatus.RECEIVED, transition.origin());
        assertEquals(WorkOrderStatus.IN_DIAGNOSIS, transition.destination());
    }

    @Test
    void skippingAStatusIsRejected() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEIVED.requireTransitionTo(WorkOrderStatus.QUOTED));

        assertEquals(WorkOrderStatus.RECEIVED, exception.origin());
        assertEquals(WorkOrderStatus.QUOTED, exception.destination());
    }

    @Test
    void aDeliveredOrderNeverReopens() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.DELIVERED.requireTransitionTo(WorkOrderStatus.IN_PROGRESS));

        assertEquals(WorkOrderStatus.DELIVERED, exception.origin());
    }

    @Test
    void theThreeTerminalStatesNeverLeave() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.DELIVERED.requireTransitionTo(WorkOrderStatus.READY));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.REJECTED.requireTransitionTo(WorkOrderStatus.IN_DIAGNOSIS));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.CANCELLED.requireTransitionTo(WorkOrderStatus.RECEIVED));
    }

    @Test
    void stayingAtTheSameStatusIsNotATransition() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.IN_PROGRESS.requireTransitionTo(WorkOrderStatus.IN_PROGRESS));
    }

    @Test
    void aNullDestinationIsRejectedWithTheExplicitException() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEIVED.requireTransitionTo(null));

        assertNull(exception.destination());
    }

    @Test
    void qualityControlBackToExecutionIsTheOnlyStepBackwards() {
        assertTrue(WorkOrderStatus.IN_QUALITY_CONTROL.requireTransitionTo(WorkOrderStatus.IN_PROGRESS) != null);
        assertFalse(WorkOrderStatus.IN_PROGRESS.canTransitionTo(WorkOrderStatus.QUOTED));
        assertFalse(WorkOrderStatus.IN_DIAGNOSIS.canTransitionTo(WorkOrderStatus.RECEIVED));
    }

    @Test
    void laborWithoutPartsAdvancesWithoutQuoting() {
        assertTrue(WorkOrderStatus.IN_DIAGNOSIS.requireTransitionTo(WorkOrderStatus.IN_PROGRESS) != null);
    }

    @Test
    void cancellationWithLiveReservationOnlyAtQuoted() {
        assertTrue(WorkOrderStatus.QUOTED.admitsCancellationWithLiveReservation());
        assertFalse(WorkOrderStatus.IN_DIAGNOSIS.admitsCancellationWithLiveReservation());
        assertFalse(WorkOrderStatus.IN_PROGRESS.admitsCancellationWithLiveReservation());
    }

    @Test
    void terminalStatesReportThemselvesAsTerminal() {
        assertTrue(WorkOrderStatus.DELIVERED.isTerminal());
        assertTrue(WorkOrderStatus.REJECTED.isTerminal());
        assertTrue(WorkOrderStatus.CANCELLED.isTerminal());
        assertFalse(WorkOrderStatus.RECEIVED.isTerminal());
    }
}
