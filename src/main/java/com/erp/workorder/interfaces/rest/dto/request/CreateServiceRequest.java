package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateServiceRequest(
        @NotNull UUID branchId,
        @NotBlank String name,
        @NotNull @DecimalMin("0.00") BigDecimal price
) {}
