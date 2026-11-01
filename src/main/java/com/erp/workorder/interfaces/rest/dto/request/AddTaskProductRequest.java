package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddTaskProductRequest(
        @NotNull UUID productId,
        @Min(1) int quantity,
        BigDecimal unitPrice
) {}
