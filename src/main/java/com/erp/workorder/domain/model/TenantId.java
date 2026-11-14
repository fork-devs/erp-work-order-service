package com.erp.workorder.domain.model;

import java.util.UUID;

/** Logical reference to the tenant (taller) that owns the order. */
public record TenantId(UUID id) {

    public static final UUID NIL_UUID = new UUID(0L, 0L);

    public TenantId {
        if (id == null) {
            throw new IllegalArgumentException("the tenant is mandatory");
        }
        if (NIL_UUID.equals(id)) {
            throw new IllegalArgumentException("the tenant cannot be the nil UUID");
        }
    }

    public static TenantId fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("the tenant is mandatory");
        }
        return new TenantId(UUID.fromString(raw.trim()));
    }
}
