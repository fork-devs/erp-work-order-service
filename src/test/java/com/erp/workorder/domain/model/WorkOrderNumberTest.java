package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkOrderNumberTest {

    @Test
    void elFormatoEsCorrelativoLegible() {
        assertEquals("WO-000001", WorkOrderNumber.of(1).valor());
        assertEquals("WO-000417", WorkOrderNumber.of(417).valor());
    }

    @Test
    void elCorrelativoNoDesciende() {
        assertThrows(IllegalArgumentException.class, () -> WorkOrderNumber.of(0));
        assertThrows(IllegalArgumentException.class, () -> WorkOrderNumber.of(-3));
    }
}
