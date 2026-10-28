package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item of the API's adding request.
 */
public record WorkOrderItemRequest(
        @NotNull(message = "{validation.work-order.item.spare-part-id.not-null}") UUID sparePartId,
        @Positive(message = "{validation.work-order.item.quantity.positive}") int quantity,
        @NotNull(message = "{validation.work-order.item.labor-price.not-null}") BigDecimal laborPrice
) {
}
