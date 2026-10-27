package com.erp.workorder.application.dto.command;

import java.util.UUID;

/**
 * The quotation of a work order: the parts already registered on the order
 * are reserved in the inventory, and ONLY when every reservation succeeds
 * does the order land at COTIZADA. One operation, one transaction, one
 * atomic outcome.
 */
public record QuoteWorkOrderCommand(
        UUID tenantId,
        UUID orderId,
        UUID changedBy
) {
}
