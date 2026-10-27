package com.erp.workorder.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A part of the order: the logical reference to the inventory part, the
 * quantity and the price of the labor associated with it. The service model
 * does not query the inventory: the availability guarantee comes from the
 * saga reservation when quoting, and the part price is whatever the workshop
 * quotes with the order.
 */
public record WorkOrderItem(
        SparePartRef sparePart,
        int quantity,
        BigDecimal laborPrice
) {

    public WorkOrderItem {
        if (sparePart == null) {
            throw new IllegalArgumentException("a work order item requires a spare part reference");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("the quantity of a work order item must be positive");
        }
        if (laborPrice == null || laborPrice.signum() < 0) {
            throw new IllegalArgumentException("the labor price cannot be negative");
        }
    }
}
