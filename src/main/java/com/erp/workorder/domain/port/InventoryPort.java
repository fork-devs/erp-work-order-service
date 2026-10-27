package com.erp.workorder.domain.port;

import java.util.UUID;

/**
 * The inventory's stock saga seen by the application: reserve, consume and
 * release. The domain defines ONLY the request and the confirmation, never
 * the transport; the guarantees of the internal call (deadline of 500 ms,
 * one retrial with the same key, circuit breaker) live in the HTTP client
 * adapter that implements this port, so a test can replace the wire
 * without touching the use case.
 */
public interface InventoryPort {

    /** Registers the reservation of a part for the order. */
    StockConfirmation reserve(StockReservation reservation);

    /** Consumes a live reservation when the quote is approved. */
    StockConfirmation commit(StockReservation reservation);

    /** Releases a live reservation so the stock returns to the lots. */
    StockConfirmation release(StockReservation reservation);

    /**
     * One unit to move: a part, its branch, the order that drives the saga
     * and the idempotency key derived from the order AND the part
     * ({@code wo:{orderId}:reserve:{partId}}), because the claim's
     * uniqueness in the inventory is (tenant_id, idempotency_key).
     */
    record StockReservation(
            UUID sparePartId,
            UUID branchId,
            int quantity,
            UUID workOrderId,
            String idempotencyKey) {
    }

    /** What the inventory registered: the movement that holds the claim. */
    record StockConfirmation(UUID movementId, String movementType, int quantity) {
    }
}
