package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The API's opening request. It has NO tenant field ON PURPOSE: the tenant
 * is the verified token's, and the caller who tries to slip another tenant
 * in the body simply does not find where.
 */
public record OpenWorkOrderRequest(
        @NotNull UUID customerId,
        @NotNull UUID vehicleId,
        @NotNull UUID branchId,
        UUID mechanicId,
        @NotBlank String diagnosis
) {
}
