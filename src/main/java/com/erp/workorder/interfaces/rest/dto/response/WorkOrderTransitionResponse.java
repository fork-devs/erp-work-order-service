package com.erp.workorder.interfaces.rest.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * The API's view of a move: the status reached and the one it left, plus
 * the instant and the actor of the transition.
 */
public record WorkOrderTransitionResponse(
        UUID orderId,
        String status,
        String origin,
        Instant closedAt
) {
}
