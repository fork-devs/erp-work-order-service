package com.erp.workorder.application.exception;

/**
 * The inventory (or fleet) is not available for the saga call: the deadline
 * expired, the transport failed after the retry, or the circuit breaker is
 * open. The availability failure is fast and explicit, never a silent hang,
 * and it is NOT reported as a business rejection of the order.
 *
 * <p>The transaction of the use case rolls back with this exception: the
 * order keeps its previous status, with no history row and no event.
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message) {
        super(message);
    }

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
