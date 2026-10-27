package com.erp.workorder.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The order-cancelled event: the workshop cancelled the order. Same delivery
 * contract as WorkOrderCompleted: persisted with the status change, published
 * later by the relay, carrying tenant and order identifiers so downstream
 * consumers (billing, in a later change) notice the cancelled order.
 */
public record WorkOrderCancelled(
        UUID eventId,
        UUID tenantId,
        UUID workOrderId,
        String workOrderNumber,
        UUID customerId,
        UUID branchId,
        String previousStatus,
        Instant occurredOn
) {
}
