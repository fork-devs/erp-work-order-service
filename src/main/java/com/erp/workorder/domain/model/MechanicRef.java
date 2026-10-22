package com.erp.workorder.domain.model;

import java.util.UUID;

/** Referencia logica al mecanico (auth). Puede quedar sin asignar: es opcional. */
public record MechanicRef(UUID id) {
    public MechanicRef {
        if (id == null) {
            throw new IllegalArgumentException("el mecanico, cuando se asigna, lleva su identificador");
        }
    }
}
