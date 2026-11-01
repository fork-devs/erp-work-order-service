package com.erp.workorder.interfaces.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID workOrderId,
        UUID serviceId,
        UUID assignedMechanicId,
        String description,
        String status,
        List<String> evidenceImages,
        Instant startedAt,
        Instant completedAt,
        List<TaskProductResponse> products,
        BigDecimal totalAmount,
        Instant createdAt
) {}
