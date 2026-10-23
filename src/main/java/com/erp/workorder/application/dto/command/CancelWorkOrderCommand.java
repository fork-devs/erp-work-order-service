package com.erp.workorder.application.dto.command;

import java.util.UUID;

/**
 * The cancellation of a work order. The release ONLY runs when the
 * cancellation breaks a LIVE reservation (a COTIZADA order); a
 * cancellation of an order that never quoted has nothing to return and
 * does not touch the inventory.
 */
public record CancelWorkOrderCommand(
        UUID tenantId,
        UUID orderId,
        UUID changedBy
) {
}
