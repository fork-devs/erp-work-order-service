package com.erp.workorder.domain.model;

import java.util.UUID;

/** Logical reference to the vehicle (fleet). Fleet verifies its existence when the order opens. */
public record VehicleRef(UUID id) {
    public VehicleRef {
        if (id == null) {
            throw new IllegalArgumentException("a work order requires a vehicle reference");
        }
    }
}
