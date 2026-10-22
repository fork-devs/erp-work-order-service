package com.erp.workorder.domain.model;

import java.util.UUID;

/** Referencia logica al cliente (fleet). Existencia no se valida localmente. */
public record CustomerRef(UUID id) {
    public CustomerRef {
        if (id == null) {
            throw new IllegalArgumentException("el cliente es obligatorio");
        }
    }
}
