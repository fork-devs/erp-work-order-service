package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Unique identifier of a work order task.
 */
public record TaskId(UUID value) {

    public TaskId {
        if (value == null) {
            throw new IllegalArgumentException("task id is mandatory");
        }
    }

    public static TaskId generate() {
        return new TaskId(UUID.randomUUID());
    }

    public static TaskId of(UUID value) {
        return new TaskId(value);
    }
}
