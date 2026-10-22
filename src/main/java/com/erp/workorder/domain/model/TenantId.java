package com.erp.workorder.domain.model;

import java.util.UUID;

/** Logical reference to the tenant (taller) that owns the order. */
public record TenantId(UUID id) {

    public TenantId {
        if (id == null) {
            throw new IllegalArgumentException("the tenant is mandatory");
        }
    }
}
