package com.erp.workorder.application.exception;

/**
 * The transition belongs to the stock saga (quote, approve, reject, cancel
 * with a live reservation), so it CANNOT run through the generic
 * transition use case: the saga's guarantees (reserve, commit, release
 * before the status moves, one transaction, idempotency keys) live in
 * their own use cases. The callers are pointed to the right door instead
 * of getting a silent bypass of the reservation.
 */
public class SagaReservedTransitionException extends RuntimeException {

    public SagaReservedTransitionException(String message) {
        super(message);
    }
}
