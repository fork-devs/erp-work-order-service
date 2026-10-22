package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * What the opening leaves: the order identifier, its per-tenant number in
 * legible form and the status it starts with.
 */
public record OpenWorkOrderResult(
        UUID orderId,
        String number,
        WorkOrderStatus status,
        UUID tenantId
) {
}
