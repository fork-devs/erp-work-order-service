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
 *   <li>A status is skipped: RECEPCIONADA -&gt; COTIZADA is rejected.</li>
 *   <li>A delivered order reopens: ENTREGADA -&gt; EN_EJECUCION is rejected.</li>
 *   <li>The allowed transition applies: RECEPCIONADA -&gt; EN_DIAGNOSTICO.</li>
 * </ul>
 */
class WorkOrderStatusTest {

    @Test
    void allowedTransitionApplies() {
        WorkOrderStatus.Transition transition = WorkOrderStatus.RECEPCIONADA.requireTransitionTo(WorkOrderStatus.EN_DIAGNOSTICO);

        assertEquals(WorkOrderStatus.RECEPCIONADA, transition.origin());
        assertEquals(WorkOrderStatus.EN_DIAGNOSTICO, transition.destination());
    }

    @Test
    void skippingAStatusIsRejected() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEPCIONADA.requireTransitionTo(WorkOrderStatus.COTIZADA));

        assertEquals(WorkOrderStatus.RECEPCIONADA, exception.origin());
        assertEquals(WorkOrderStatus.COTIZADA, exception.destination());
    }

    @Test
    void aDeliveredOrderNeverReopens() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.ENTREGADA.requireTransitionTo(WorkOrderStatus.EN_EJECUCION));

        assertEquals(WorkOrderStatus.ENTREGADA, exception.origin());
    }

    @Test
    void theThreeTerminalStatesNeverLeave() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.ENTREGADA.requireTransitionTo(WorkOrderStatus.LISTA));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.RECHAZADA.requireTransitionTo(WorkOrderStatus.EN_DIAGNOSTICO));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.CANCELADA.requireTransitionTo(WorkOrderStatus.RECEPCIONADA));
    }

    @Test
    void stayingAtTheSameStatusIsNotATransition() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.EN_EJECUCION.requireTransitionTo(WorkOrderStatus.EN_EJECUCION));
    }

    @Test
    void aNullDestinationIsRejectedWithTheExplicitException() {
        InvalidWorkOrderTransitionException exception = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEPCIONADA.requireTransitionTo(null));

        assertNull(exception.destination());
    }

    @Test
    void qualityControlBackToExecutionIsTheOnlyStepBackwards() {
        assertTrue(WorkOrderStatus.EN_CONTROL_CALIDAD.requireTransitionTo(WorkOrderStatus.EN_EJECUCION) != null);
        assertFalse(WorkOrderStatus.EN_EJECUCION.canTransitionTo(WorkOrderStatus.COTIZADA));
        assertFalse(WorkOrderStatus.EN_DIAGNOSTICO.canTransitionTo(WorkOrderStatus.RECEPCIONADA));
    }

    @Test
    void laborWithoutPartsAdvancesWithoutQuoting() {
        assertTrue(WorkOrderStatus.EN_DIAGNOSTICO.requireTransitionTo(WorkOrderStatus.EN_EJECUCION) != null);
    }

    @Test
    void cancellationWithLiveReservationOnlyAtQuoted() {
        assertTrue(WorkOrderStatus.COTIZADA.admitsCancellationWithLiveReservation());
        assertFalse(WorkOrderStatus.EN_DIAGNOSTICO.admitsCancellationWithLiveReservation());
        assertFalse(WorkOrderStatus.EN_EJECUCION.admitsCancellationWithLiveReservation());
    }

    @Test
    void terminalStatesReportThemselvesAsTerminal() {
        assertTrue(WorkOrderStatus.ENTREGADA.isTerminal());
        assertTrue(WorkOrderStatus.RECHAZADA.isTerminal());
        assertTrue(WorkOrderStatus.CANCELADA.isTerminal());
        assertFalse(WorkOrderStatus.RECEPCIONADA.isTerminal());
    }
}
