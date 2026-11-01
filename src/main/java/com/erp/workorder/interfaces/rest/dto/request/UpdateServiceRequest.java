package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record UpdateServiceRequest(
        String name,
        @DecimalMin("0.00") BigDecimal price
) {}
