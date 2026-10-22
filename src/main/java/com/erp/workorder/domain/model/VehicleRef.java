package com.erp.workorder.domain.model;

import java.util.UUID;

/** Referencia logica al vehiculo (fleet). La existencia la verifica fleet al abrir la orden. */
public record VehicleRef(UUID id) {
    public VehicleRef {
        if (id == null) {
            throw new IllegalArgumentException("el vehiculo es obligatorio");
        }
    }
}
