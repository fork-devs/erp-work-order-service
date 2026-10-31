package com.erp.workorder.domain.model;

/**
 * State machine representing the progress of a workshop task.
 */
public enum TaskStatus {
    PENDING,
    DOING,
    COMPLETED;

    public boolean canTransitionTo(TaskStatus destination) {
        if (this == destination) {
            return false;
        }
        return switch (this) {
            case PENDING -> destination == DOING;
            case DOING -> destination == COMPLETED;
            case COMPLETED -> destination == DOING; // Reopen for rework
        };
    }
}
