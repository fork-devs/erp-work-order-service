package com.erp.workorder.infrastructure.client;

/**
 * One saga operation over the inventory's stock: reserve, commit or
 * release. The idempotency key is derived from the order and the part
 * ({@code wo:{orderId}:reserve:{partId}}), because the inventory's claim
 * uniqueness is (tenant_id, idempotency_key): the key must distinguish
 * every operation of the order.
 */
public record SagaStockOperation(
        java.util.UUID sparePartId,
        java.util.UUID branchId,
        int quantity,
        java.util.UUID workOrderId,
        String idempotencyKey
) {
}
