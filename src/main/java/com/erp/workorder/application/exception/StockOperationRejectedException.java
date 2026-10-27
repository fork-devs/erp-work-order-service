package com.erp.workorder.application.exception;

/**
 * The inventory REJECTED the saga operation: insufficient stock, a delta
 * the stored data cannot satisfy, or an authorization the shared roles do
 * not cover. It is a business answer, not an availability failure — the
 * idempotent retry does not repeat it (a deterministic rejection would stay
 * the same), and the transaction of the use case rolls back with it.
 */
public class StockOperationRejectedException extends RuntimeException {

    private final String inventoryCode;

    public StockOperationRejectedException(String message, String inventoryCode) {
        super(message);
        this.inventoryCode = inventoryCode;
    }

    /** The stable error code the inventory's ProblemDetail carried, when parseable. */
    public String inventoryCode() {
        return inventoryCode;
    }
}
