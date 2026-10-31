package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Unique identifier of an allocated product line inside a task.
 */
public record TaskProductId(UUID value) {

    public TaskProductId {
        if (value == null) {
            throw new IllegalArgumentException("task product id is mandatory");
        }
    }

    public static TaskProductId generate() {
        return new TaskProductId(UUID.randomUUID());
    }

    public static TaskProductId of(UUID value) {
        return new TaskProductId(value);
    }
}
