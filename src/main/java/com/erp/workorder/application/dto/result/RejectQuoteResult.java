package com.erp.workorder.application.dto.result;

import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.UUID;

/**
 * The rejection outcome: the order closed at RECHAZADA and the reserved
 * stock returned, or nothing — on failure the caller receives the failure
 * and the reservation stays alive.
 */
public record RejectQuoteResult(
        UUID orderId,
        WorkOrderStatus status,
        WorkOrderStatus origin
) {
}
