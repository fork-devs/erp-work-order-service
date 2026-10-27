package com.erp.workorder.application.exception;

/**
 * The order the operation points at does not exist in the tenant of the
 * authenticated principal. The absence is indistinguishable from a foreign
 * tenant's order on purpose: the isolation is not leaky even through errors.
 */
public class WorkOrderNotFoundException extends RuntimeException {

    public WorkOrderNotFoundException(String message) {
        super(message);
    }
}
