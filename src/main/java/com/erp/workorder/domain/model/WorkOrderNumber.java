package com.erp.workorder.domain.model;

/**
 * El correlativo de tarea por taller, en su formato humane: {@code WO-000001}.
 * La unicidad (tenant_id, number) la arbitra la base de datos; este tipo solo
 * le da forma legible al numero que el contador por taller reservo.
 */
public record WorkOrderNumber(int secuencial) {

    private static final int SECUENCIA_MINIMA = 1;
    private static final int DIGITOS = 6;

    public WorkOrderNumber {
        if (secuencial < SECUENCIA_MINIMA) {
            throw new IllegalArgumentException("el correlativo no desciende: se recibio " + secuencial);
        }
    }

    public static WorkOrderNumber of(int secuencial) {
        return new WorkOrderNumber(secuencial);
    }

    public String valor() {
        return "WO-" + String.format("%0" + DIGITOS + "d", secuencial);
    }

    public int secuencial() {
        return secuencial;
    }

    @Override
    public String toString() {
        return valor();
    }
}
