package com.erp.workorder.domain.model;

/**
 * The nine states of the work order lifecycle, with the declared TRANSITIONS
 * as part of the type itself.
 *
 * <p>The rule is about process, not data: a transition the domain does not
 * declare is rejected explicitly and leaves no trace (no new status, no
 * history). That is exactly what the spec demands: "the system rejects the
 * transition and the order stays in its previous status".
 *
 * <p>Two non-obvious transitions are declared on purpose:
 *
 * <ul>
 *   <li>{@code IN_DIAGNOSIS -> IN_PROGRESS}: labor WITHOUT parts does not
 *   need to go through quoting; the order advances with no saga call.</li>
 *   <li>{@code IN_QUALITY_CONTROL -> IN_PROGRESS}: a finding in quality
 *   control sends the order back to the mechanic, and it is the ONLY step
 *   backwards the lifecycle allows.</li>
 * </ul>
 *
 * <p>{@code DELIVERED}, {@code REJECTED} and {@code CANCELLED} are TERMINAL:
 * no transition leaves them, and an order never returns to an active status
 * without a declared transition. The state names keep the Spanish code the
 * whole platform uses (they are persisted as literal DB values, matching the
 * schema CHECK constraints in V1).
 */
public enum WorkOrderStatus {

    RECEIVED,
    IN_DIAGNOSIS,
    QUOTED,
    IN_PROGRESS,
    IN_QUALITY_CONTROL,
    READY,
    DELIVERED,
    REJECTED,
    CANCELLED;

    private static final java.util.Map<WorkOrderStatus, java.util.Set<WorkOrderStatus>> TRANSITIONS = java.util.Map.of(
            RECEIVED, java.util.Set.of(IN_DIAGNOSIS, CANCELLED),
            IN_DIAGNOSIS, java.util.Set.of(QUOTED, IN_PROGRESS, CANCELLED),
            QUOTED, java.util.Set.of(IN_PROGRESS, REJECTED, CANCELLED),
            IN_PROGRESS, java.util.Set.of(IN_QUALITY_CONTROL),
            IN_QUALITY_CONTROL, java.util.Set.of(READY, IN_PROGRESS),
            READY, java.util.Set.of(DELIVERED),
            DELIVERED, java.util.Set.of(),
            REJECTED, java.util.Set.of(),
            CANCELLED, java.util.Set.of()
    );

    /** The transition itself: origin + destination in one immutable value, for errors and history. */
    public record Transition(WorkOrderStatus origin, WorkOrderStatus destination) {
    }

    /**
     * Requires the declared transition and returns it; rejects with the
     * domain exception when the state machine does not declare it.
     */
    public Transition requireTransitionTo(WorkOrderStatus destination) {
        if (destination == null) {
            throw new InvalidWorkOrderTransitionException(this, null);
        }
        if (destination == this) {
            throw new InvalidWorkOrderTransitionException(this, destination);
        }
        if (!TRANSITIONS.get(this).contains(destination)) {
            throw new InvalidWorkOrderTransitionException(this, destination);
        }
        return new Transition(this, destination);
    }

    /** Answers whether the machine declares the transition; the use case decides which call accompanies it. */
    public boolean canTransitionTo(WorkOrderStatus destination) {
        return destination != null && destination != this && TRANSITIONS.get(this).contains(destination);
    }

    /**
     * Declared cancellation: the one status where the workshop may cancel a
     * live reservation. The reservation lives only at QUOTED, so only there
     * is there anything to release.
     */
    public boolean admitsCancellationWithLiveReservation() {
        return this == QUOTED;
    }

    /** The order is closed: no movement touches it again. */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
