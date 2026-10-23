package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * The cancellation outcome: the order closed at CANCELADA — with the live
 * reservation returned when there was one — or nothing.
 */
public record CancelWorkOrderResult(
        UUID orderId,
        WorkOrderStatus status,
        WorkOrderStatus origin
) {
}
