package com.erp.workorder.application.dto.command;

import java.util.UUID;

/**
 * The client's approval of a quotation: the reserved parts are consumed in
 * the inventory and the order only then moves to EN_EJECUCION. One
 * operation, one transaction, one atomic outcome.
 */
public record ApproveQuoteCommand(
        UUID tenantId,
        UUID orderId,
        UUID changedBy
) {
}
