package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The request adding items to an order: the logical part references and
 * their quantities, next to the labor price the workshop quotes.
 */
public record AddItemsRequest(
        @Valid @NotEmpty(message = "{validation.work-order.items.not-empty}") List<WorkOrderItemRequest> items
) {
}
