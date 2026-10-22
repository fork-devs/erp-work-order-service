package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La maquina de estados, sin nada a su alrededor: ninguna regla su spring, y
 * todos los escenarios de la spec son llamadas directas al dominio.
 *
 * <ul>
 *   <li>Se salta un estado: RECEPCIONADA -> COTIZADA se rechaza.</li>
 *   <li>Se reabre una orden entregada: ENTREGADA -> EN_EJECUCION se rechaza.</li>
 *   <li>La transicion permitida se aplica: RECEPCIONADA -> EN_DIAGNOSTICO.</li>
 * </ul>
 */
class WorkOrderStatusTest {

    @Test
    void laTransicionPermitidaSeAplica() {
        WorkOrderStatus.Transicion transicion = WorkOrderStatus.RECEPCIONADA.requerirTransicionA(WorkOrderStatus.EN_DIAGNOSTICO);

        assertEquals(WorkOrderStatus.RECEPCIONADA, transicion.origen());
        assertEquals(WorkOrderStatus.EN_DIAGNOSTICO, transicion.destino());
    }

    @Test
    void seSaltaUnEstadoYElDominoLoRechaza() {
        InvalidWorkOrderTransitionException excepcion = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEPCIONADA.requerirTransicionA(WorkOrderStatus.COTIZADA));

        assertEquals(WorkOrderStatus.RECEPCIONADA, excepcion.origen());
        assertEquals(WorkOrderStatus.COTIZADA, excepcion.destino());
    }

    @Test
    void unaOrdenEntregadaNoReabre() {
        InvalidWorkOrderTransitionException excepcion = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.ENTREGADA.requerirTransicionA(WorkOrderStatus.EN_EJECUCION));

        assertEquals(WorkOrderStatus.ENTREGADA, excepcion.origen());
    }

    @Test
    void losTresEstadosTerminalesNoSalen() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.ENTREGADA.requerirTransicionA(WorkOrderStatus.LISTA));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.RECHAZADA.requerirTransicionA(WorkOrderStatus.EN_DIAGNOSTICO));
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.CANCELADA.requerirTransicionA(WorkOrderStatus.RECEPCIONADA));
    }

    @Test
    void noSePuedeTransicionarAlMismoEstado() {
        assertThrows(InvalidWorkOrderTransitionException.class, () -> WorkOrderStatus.EN_EJECUCION.requerirTransicionA(WorkOrderStatus.EN_EJECUCION));
    }

    @Test
    void unDestinoNuloSeRechazaConLaExcepcionExplicita() {
        InvalidWorkOrderTransitionException excepcion = assertThrows(
                InvalidWorkOrderTransitionException.class,
                () -> WorkOrderStatus.RECEPCIONADA.requerirTransicionA(null));

        assertNull(excepcion.destino());
    }

    @Test
    void laVueltaDeControlCalidadAEjecucionEsLaUnicaHaciaAtras() {
        assertTrue(WorkOrderStatus.EN_CONTROL_CALIDAD.requerirTransicionA(WorkOrderStatus.EN_EJECUCION) != null);
        assertFalse(WorkOrderStatus.EN_EJECUCION.puedeTransicionarA(WorkOrderStatus.COTIZADA));
        assertFalse(WorkOrderStatus.EN_DIAGNOSTICO.puedeTransicionarA(WorkOrderStatus.RECEPCIONADA));
    }

    @Test
    void elTrabajoSinRepuestosAvanzaSinCotizar() {
        assertTrue(WorkOrderStatus.EN_DIAGNOSTICO.requerirTransicionA(WorkOrderStatus.EN_EJECUCION) != null);
    }

    @Test
    void laCancelacionConReservaVivaSoloEnCotizada() {
        assertTrue(WorkOrderStatus.COTIZADA.admiteCancelacionConReservaViva());
        assertFalse(WorkOrderStatus.EN_DIAGNOSTICO.admiteCancelacionConReservaViva());
        assertFalse(WorkOrderStatus.EN_EJECUCION.admiteCancelacionConReservaViva());
    }

    @Test
    void losTerminalesSeReportanComoTerminales() {
        assertTrue(WorkOrderStatus.ENTREGADA.esTerminal());
        assertTrue(WorkOrderStatus.RECHAZADA.esTerminal());
        assertTrue(WorkOrderStatus.CANCELADA.esTerminal());
        assertFalse(WorkOrderStatus.RECEPCIONADA.esTerminal());
    }
}
