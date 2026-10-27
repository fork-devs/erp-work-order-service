package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The result of a transition: the order with its new status and the closure
 * instant when the transition was terminal.
 */
public record TransitionWorkOrderResult(
        UUID orderId,
        WorkOrderStatus status,
        WorkOrderStatus origin,
        Instant closedAt
) {
}
