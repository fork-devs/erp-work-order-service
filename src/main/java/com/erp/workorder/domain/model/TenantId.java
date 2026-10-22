package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Referencias logicas a otras bases: el servicio de ordin no conoce el
 * esquema de fleet (clientes/vehiculos), de auth (mecanico) ni del inventario
 * (sucursal y repuestos). Ninguna FK cruza el limite, y la existencia del
 * material referenciado no la valida este modelo: la garantia de la reserva
 * la da la saga, y la del vehiculo la verifica fleet en la apertura.
 */
public record TenantId(UUID id) {

    public TenantId {
        if (id == null) {
            throw new IllegalArgumentException("el taller es obligatorio");
        }
    }
}
