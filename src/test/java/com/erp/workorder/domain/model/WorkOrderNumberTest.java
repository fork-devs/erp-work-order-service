package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkOrderNumberTest {

    @Test
    void theFormatIsALegibleCorrelative() {
        assertEquals("WO-000001", WorkOrderNumber.of(1).value());
        assertEquals("WO-000417", WorkOrderNumber.of(417).value());
    }

    @Test
    void theCorrelativeNeverGoesBack() {
        assertThrows(IllegalArgumentException.class, () -> WorkOrderNumber.of(0));
        assertThrows(IllegalArgumentException.class, () -> WorkOrderNumber.of(-3));
    }
}
