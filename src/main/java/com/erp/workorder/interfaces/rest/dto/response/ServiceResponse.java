package com.erp.workorder.interfaces.rest.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        UUID branchId,
        String name,
        BigDecimal price
) {}
