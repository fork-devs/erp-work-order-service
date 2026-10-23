package com.erp.workorder.application.dto.command;

import java.util.UUID;

/**
 * The client does NOT approve the quotation: the reserved parts are
 * released back into the inventory's lots and the order closes as
 * RECHAZADA.
 */
public record RejectQuoteCommand(
        UUID tenantId,
        UUID orderId,
        UUID changedBy
) {
}
