package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item of the API's adding request.
 */
public record WorkOrderItemRequest(
        @NotNull UUID sparePartId,
        @Positive int quantity,
        @NotNull BigDecimal laborPrice
) {
}
