package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Referencias logicas al inventario que se reservan y consumen los repuestos
 * de la orden. La orden no valida su existencia: la garantia la da la reserva
 * de la saga.
 */
public record BranchId(UUID id) {

    public BranchId {
        if (id == null) {
            throw new IllegalArgumentException("la sucursal es obligatoria");
        }
    }
}
