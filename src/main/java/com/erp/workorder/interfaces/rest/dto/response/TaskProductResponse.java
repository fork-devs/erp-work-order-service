package com.erp.workorder.interfaces.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaskProductResponse(
        UUID id,
        UUID productId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        String approvalStatus,
        Instant createdAt
) {
    public TaskProductResponse(
            UUID id,
            UUID productId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalAmount,
            Instant createdAt
    ) {
        this(id, productId, quantity, unitPrice, totalAmount, "CUSTOMER_APPROVED", createdAt);
    }
}
