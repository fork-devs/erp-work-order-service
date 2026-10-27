package com.erp.workorder.infrastructure.persistence.rls;

/**
 * Thrown when an operation that requires tenant isolation runs without a
 * tenant context established.
 *
 * <p>The isolation is never relaxed silently: an operation without a
 * context is not executed without a filter, it is REJECTED (fail-closed) —
 * a query without context must fail, not return zero rows pretending
 * nothing was there.
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException(String message) {
        super(message);
    }
}
