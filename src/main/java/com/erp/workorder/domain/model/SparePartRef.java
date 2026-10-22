package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Referencia logica al repuesto (inventario). Su existencia el inventario la
 * garantiza en la reserva, no este modelo.
 */
public record SparePartRef(UUID id) {
    public SparePartRef {
        if (id == null) {
            throw new IllegalArgumentException("el repuesto lleva obligatoriamente el identificador");
        }
    }
}
