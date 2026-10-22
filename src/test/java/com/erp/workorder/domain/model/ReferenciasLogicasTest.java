package com.erp.workorder.domain.model;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Las referencias logicas no llevan a una base de datos ajena al servicio ni
 * saturan su esquema. Ninguno es un UUID packaging otra vez; se pide solo el
 * identificador. La existencia del material referenciado no la valida este
 * modelo — spec: «el sistema lo registra igualmente».
 */
class ReferenciasLogicasTest {

    private static final UUID UN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void unaOrdenConUnRepuestoQueElInventarioNoConoceSeRegistra() {
        assertDoesNotThrow(() -> new SparePartRef(UN_ID));
        assertDoesNotThrow(() -> new MechanicRef(UN_ID));
        assertDoesNotThrow(() -> new CustomerRef(UN_ID));
        assertDoesNotThrow(() -> new VehicleRef(UN_ID));
    }

    @Test
    void lasReferenciasObligatoriasNoAdmitenNulo() {
        assertThrows(IllegalArgumentException.class, () -> new CustomerRef(null));
        assertThrows(IllegalArgumentException.class, () -> new VehicleRef(null));
        assertThrows(IllegalArgumentException.class, () -> new SparePartRef(null));
        assertThrows(IllegalArgumentException.class, () -> new BranchId(null));
        assertThrows(IllegalArgumentException.class, () -> new TenantId(null));
    }

    @Test
    void elMecanicoEsOpcionalPeroNoNuloCuandoExiste() {
        assertThrows(IllegalArgumentException.class, () -> new MechanicRef(null));
    }
}
