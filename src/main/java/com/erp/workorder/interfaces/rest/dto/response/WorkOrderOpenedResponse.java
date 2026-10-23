package com.erp.workorder.interfaces.rest.dto.response;

import java.util.UUID;

/**
 * The API's view of an order's identity after an opening.
 */
public record WorkOrderOpenedResponse(
        UUID orderId,
        String number,
        String status,
        UUID tenantId
) {
}
