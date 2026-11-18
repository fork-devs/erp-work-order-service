package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * The approval outcome: the order at IN_PROGRESS with the status it came
 * from, or nothing — on failure the caller receives the failure instead of
 * an order that consumes stock it never confirmed.
 */
public record ApproveQuoteResult(
        UUID orderId,
        WorkOrderStatus status,
        WorkOrderStatus origin
) {
}
