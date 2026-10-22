package com.erp.workorder.domain.model;

/**
 * La transicion entre estados que el dominio no declara. La orden no cambia de
 * estado, no deja fila de historia, y el fallo es del CLIENTE del dominio: la
 * historia inmutable no registra transiciones que no ocurrieron.
 */
public class InvalidWorkOrderTransitionException extends RuntimeException {

    private final WorkOrderStatus origen;
    private final WorkOrderStatus destino;

    public InvalidWorkOrderTransitionException(WorkOrderStatus origen, WorkOrderStatus destino) {
        super("la transicion " + origen + " -> " + destino + " no esta declarada");
        this.origen = origen;
        this.destino = destino;
    }

    public WorkOrderStatus origen() {
        return origen;
    }

    public WorkOrderStatus destino() {
        return destino;
    }
}
