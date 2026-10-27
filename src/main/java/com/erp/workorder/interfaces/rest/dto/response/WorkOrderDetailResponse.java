package com.erp.workorder.interfaces.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The order's detail: its identity, its items (the parts and their labor)
 * and its history, exactly as the aggregate models them.
 */
public record WorkOrderDetailResponse(
        UUID orderId,
        String number,
        String status,
        String diagnosis,
        UUID customerId,
        UUID vehicleId,
        UUID branchId,
        UUID mechanicId,
        Instant openedAt,
        Instant closedAt,
        List<ItemResponse> items,
        List<HistoryEntryResponse> history
) {

    /** One part of the order as the workshop quotes it. */
    public record ItemResponse(
            UUID sparePartId,
            int quantity,
            BigDecimal laborPrice
    ) {
    }

    /** One entry of the status history. */
    public record HistoryEntryResponse(
            String from,
            String to,
            UUID changedBy,
            Instant changedAt
    ) {
    }
}
