package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.Min;

public record UpdateTaskProductQuantityRequest(
        @Min(1) int quantity
) {}
