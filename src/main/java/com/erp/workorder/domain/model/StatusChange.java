package com.erp.workorder.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the status history: origin, destination, author and time.
 * When the order is opened, the first entry has only the destination
 * ({@code transition} is null because no previous status exists).
 */
public record StatusChange(
        WorkOrderStatus.Transition transition,
        UUID changedBy,
        Instant changedAt
) {
}
