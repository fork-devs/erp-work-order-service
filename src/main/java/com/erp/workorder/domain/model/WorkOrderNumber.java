package com.erp.workorder.domain.model;

/**
 * The per-tenant correlative number of the order, in its legible format:
 * {@code WO-000001}. Uniqueness (tenant_id, number) is arbitrated by the
 * database; this type only gives a readable shape to the number the per
 * tenant counter reserved.
 */
public record WorkOrderNumber(int sequence) {

    private static final int MIN_SEQUENCE = 1;
    private static final int DIGITS = 6;

    public WorkOrderNumber {
        if (sequence < MIN_SEQUENCE) {
            throw new IllegalArgumentException("the correlative never goes back: received " + sequence);
        }
    }

    public static WorkOrderNumber of(int sequence) {
        return new WorkOrderNumber(sequence);
    }

    public String value() {
        return "WO-" + String.format("%0" + DIGITS + "d", sequence);
    }

    @Override
    public String toString() {
        return value();
    }
}
