package com.erp.workorder.application.exception;

/**
 * The fleet did not answer the vehicle verification: the deadline expired,
 * the transport failed after the retry, or the circuit breaker is open.
 * The opening fails CLOSED: no order row is written while the vehicle
 * cannot be proven to belong to the command's customer.
 */
public class FleetUnavailableException extends RuntimeException {

    public FleetUnavailableException(String message) {
        super(message);
    }

    public FleetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
