package com.erp.workorder.application.dto.command;

import java.util.List;
import java.util.UUID;

/**
 * Adds parts to an order that has not quoted yet. Accepts an empty list,
 * because a labor-without-parts order adds no item at all.
 */
public record AddItemsToWorkOrderCommand(
        UUID tenantId,
        UUID orderId,
        List<WorkOrderItemCommand> items
) {
}
