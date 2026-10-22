package com.erp.workorder.domain.model;

import java.util.UUID;

/** Logical reference to the mechanic (auth). It may stay unassigned: it is optional. */
public record MechanicRef(UUID id) {
    public MechanicRef {
        if (id == null) {
            throw new IllegalArgumentException("a mechanic, when assigned, carries its identifier");
        }
    }
}
