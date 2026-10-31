package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Unique identifier of a workshop catalog service.
 */
public record ServiceId(UUID value) {

    public ServiceId {
        if (value == null) {
            throw new IllegalArgumentException("service id is mandatory");
        }
    }

    public static ServiceId generate() {
        return new ServiceId(UUID.randomUUID());
    }

    public static ServiceId of(UUID value) {
        return new ServiceId(value);
    }
}
