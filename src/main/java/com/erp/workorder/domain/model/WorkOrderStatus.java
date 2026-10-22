package com.erp.workorder.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * Los nueve estados del ciclo de la orden de trabajo, con las TRANSICIONES
 * declaradas como parte del propio tipo.
 *
 * <p>La regla es de proceso, no de dato: una transicion que el dominio no
 * declara se rechaza de forma explicita y no deja rastro (ni estado nuevo ni
 * historia). Es la regla que la spec exige: «el sistema rechaza la transicion
 * y la orden sigue en el estado anterior».
 *
 * <p>Dos transiciones no obvias estan declaradas a proposito:
 *
 * <ul>
 *   <li>{@code EN_DIAGNOSTICO -> EN_EJECUCION}: el trabajo SIN repuestos no
 *   necesita pasar por cotizacion; la orden avanza sin saga.</li>
 *   <li>{@code EN_CONTROL_CALIDAD -> EN_EJECUCION}: el hallazgo de control
 *   devuelve la orden al mecanico, y es la UNICA vuelta a un estado anterior
 *   que el ciclo permite.</li>
 * </ul>
 *
 * <p>{@code ENTREGADA}, {@code RECHAZADA} y {@code CANCELADA} son TERMINALES:
 * ninguna transicion sale de ellas, y una orden no vuelve a estado activo sin
 * una transicion declarada.
 */
public enum WorkOrderStatus {

    RECEPCIONADA,
    EN_DIAGNOSTICO,
    COTIZADA,
    EN_EJECUCION,
    EN_CONTROL_CALIDAD,
    LISTA,
    ENTREGADA,
    RECHAZADA,
    CANCELADA;

    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> TRANSICIONES = Map.of(
            RECEPCIONADA, Set.of(EN_DIAGNOSTICO, CANCELADA),
            EN_DIAGNOSTICO, Set.of(COTIZADA, EN_EJECUCION, CANCELADA),
            COTIZADA, Set.of(EN_EJECUCION, RECHAZADA, CANCELADA),
            EN_EJECUCION, Set.of(EN_CONTROL_CALIDAD),
            EN_CONTROL_CALIDAD, Set.of(LISTA, EN_EJECUCION),
            LISTA, Set.of(ENTREGADA),
            ENTREGADA, Set.of(),
            RECHAZADA, Set.of(),
            CANCELADA, Set.of()
    );

    /** El detalle del salto: origen + destino en un solo tipo, para el error y la historia. */
    public record Transicion(WorkOrderStatus origen, WorkOrderStatus destino) {
    }

    /** Responde si el dominio declara la transicion y la devuelve; rechaza con la excepcion si no. */
    public Transicion requerirTransicionA(WorkOrderStatus destino) {
        if (destino == null) {
            throw new InvalidWorkOrderTransitionException(this, null);
        }
        if (destino == this) {
            throw new InvalidWorkOrderTransitionException(this, destino);
        }
        if (!TRANSICIONES.get(this).contains(destino)) {
            throw new InvalidWorkOrderTransitionException(this, destino);
        }
        return new Transicion(this, destino);
    }

    /** Permite a la maquina declarar la transicion; el caso de uso decide que llamada la acompana. */
    public boolean puedeTransicionarA(WorkOrderStatus destino) {
        return destino != null && destino != this && TRANSICIONES.get(this).contains(destino);
    }

    /**
     * Cancelacion declarada: los estados desde los que el taller puede
     * cancelar una orden. La spec exige que la cancelacion LIBERE la reserva
     * cuando esta viva; vive cuando la orden esta en COTIZADA.
     */
    public boolean admiteCancelacionConReservaViva() {
        return this == COTIZADA;
    }

    /** La orden ya esta cerrada: ningun movimiento la toca. */
    public boolean esTerminal() {
        return TRANSICIONES.get(this).isEmpty();
    }
}
