package com.erp.workorder.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The order-completed event: the vehicle was delivered. It is PERSISTED next
 * to the status change, in the same local transaction (Transactional Outbox),
 * and a relay publishes it afterwards — never inside the business
 * transaction, never with the status change deferred elsewhere.
 */
public record WorkOrderCompleted(
        UUID eventId,
        UUID tenantId,
        UUID workOrderId,
        String workOrderNumber,
        UUID customerId,
        UUID branchId,
        Instant occurredOn
) {
}
