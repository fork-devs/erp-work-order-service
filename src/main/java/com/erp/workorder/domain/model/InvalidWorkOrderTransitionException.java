package com.erp.workorder.domain.model;

/**
 * The transition between statuses the domain does not declare. The order
 * keeps its status, no history row is written, and the fault belongs to the
 * CALLER of the domain: the immutable history never records transitions that
 * did not happen.
 */
public class InvalidWorkOrderTransitionException extends RuntimeException {

    private final WorkOrderStatus origin;
    private final WorkOrderStatus destination;

    public InvalidWorkOrderTransitionException(WorkOrderStatus origin, WorkOrderStatus destination) {
        super("transition " + origin + " -> " + destination + " is not declared");
        this.origin = origin;
        this.destination = destination;
    }

    public WorkOrderStatus origin() {
        return origin;
    }

    public WorkOrderStatus destination() {
        return destination;
    }
}
