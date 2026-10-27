package com.erp.workorder.application.dto.command;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * The transition command. The target status is whatever the caller asks
 * for; the state machine of the domain is who declares whether the move is
 * allowed. The inventory-facing transitions (quote, approve, reject,
 * cancel) live in their own use cases of the saga (group 6).
 */
public record TransitionWorkOrderCommand(
        UUID tenantId,
        UUID orderId,
        WorkOrderStatus destination,
        UUID changedBy
) {

    public TransitionWorkOrderCommand {
        if (tenantId == null || orderId == null || destination == null) {
            throw new IllegalArgumentException("a transition requires tenant, order and destination status");
        }
    }
}
