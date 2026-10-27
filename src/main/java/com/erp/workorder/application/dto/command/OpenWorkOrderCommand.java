package com.erp.workorder.application.dto.command;

import java.util.UUID;

/**
 * The opening command of a work order. The tenant is the one of the
 * authenticated principal, passed by the interface layer; the payload of the
 * request NEVER decides it.
 */
public record OpenWorkOrderCommand(
        UUID tenantId,
        UUID customerId,
        UUID vehicleId,
        UUID branchId,
        UUID mechanicId,
        String diagnosis
) {

    public OpenWorkOrderCommand {
        if (tenantId == null || customerId == null || vehicleId == null || branchId == null) {
            throw new IllegalArgumentException(
                    "opening a work order requires tenant, customer, vehicle and branch identifiers");
        }
    }
}
