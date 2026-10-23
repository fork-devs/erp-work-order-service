package com.erp.workorder.interfaces.rest.dto.response;

import java.util.UUID;

/**
 * One row of the workshop's list view.
 */
public record WorkOrderSummaryResponse(
        UUID orderId,
        String number,
        String status,
        UUID customerId,
        UUID vehicleId,
        UUID branchId
) {
}
