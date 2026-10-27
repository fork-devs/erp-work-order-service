package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * The quotation outcome: the order at COTIZADA with the status it came
 * from, or nothing — on failure the caller receives the failure instead of
 * a half-quoted order.
 */
public record QuoteWorkOrderResult(
        UUID orderId,
        WorkOrderStatus status,
        WorkOrderStatus origin
) {
}
