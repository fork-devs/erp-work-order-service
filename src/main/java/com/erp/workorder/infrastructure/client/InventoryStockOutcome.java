package com.erp.workorder.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * The outcome the inventory answers for one stock operation: the movement
 * it registered (or the one already claimed by the earlier identical call).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryStockOutcome(
        UUID id,
        String movementType,
        int quantity,
        UUID sparePartId,
        UUID workOrderId,
        String idempotencyKey
) {
}
