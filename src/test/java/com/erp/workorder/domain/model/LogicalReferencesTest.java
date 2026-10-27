package com.erp.workorder.domain.model;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Logical references point at other services' databases and carry no foreign
 * key across the boundary. The existence of the referenced data is NOT
 * validated by this model — spec: "the system registers it anyway".
 */
class LogicalReferencesTest {

    private static final UUID AN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void anOrderRegistersAPartTheInventoryDoesNotKnow() {
        assertDoesNotThrow(() -> new SparePartRef(AN_ID));
        assertDoesNotThrow(() -> new MechanicRef(AN_ID));
        assertDoesNotThrow(() -> new CustomerRef(AN_ID));
        assertDoesNotThrow(() -> new VehicleRef(AN_ID));
    }

    @Test
    void mandatoryReferencesRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> new CustomerRef(null));
        assertThrows(IllegalArgumentException.class, () -> new VehicleRef(null));
        assertThrows(IllegalArgumentException.class, () -> new SparePartRef(null));
        assertThrows(IllegalArgumentException.class, () -> new BranchId(null));
        assertThrows(IllegalArgumentException.class, () -> new TenantId(null));
    }

    @Test
    void theMechanicIsOptionalButNotNullWhenAssigned() {
        assertThrows(IllegalArgumentException.class, () -> new MechanicRef(null));
    }
}
