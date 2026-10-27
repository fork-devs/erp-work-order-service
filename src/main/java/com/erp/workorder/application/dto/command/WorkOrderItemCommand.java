package com.erp.workorder.application.dto.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One part to reserve for the order: the logical reference to the inventory
 * part, the quantity and the quoted labor price. The inventory arbitrates
 * the existence of the part at the reservation, not this service.
 */
public record WorkOrderItemCommand(
        UUID sparePartId,
        int quantity,
        BigDecimal laborPrice
) {
}
