package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.VehicleOwnershipMismatchException;
import com.erp.workorder.application.exception.VehicleUnknownByFleetException;
import com.erp.workorder.application.logging.SecurityEventLogger;
import com.erp.workorder.domain.port.FleetPort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Verifies the vehicle against fleet BEFORE any write: the vehicle must
 * exist in the tenant and be registered to the customer of the order.
 * FAIL-CLOSED on purpose: if fleet cannot answer (unavailable, deadline,
 * open circuit) the failure propagates and NOTHING is written — an order
 * without a proven owner is not acceptable, an outage does not open it.
 *
 * <p>Every rejection is logged with the tenant it came from, so an
 * attacker probing the opening path leaves the attempt behind.
 */
@Component
public class VehicleVerificationService {

    private final FleetPort fleet;

    public VehicleVerificationService(FleetPort fleet) {
        this.fleet = fleet;
    }

    /**
     * The registration of the vehicle, with its real owner, once the
     * verification passed.
     */
    public FleetPort.VehicleRegistration verifyOpening(UUID tenantId, UUID vehicleId, UUID expectedCustomerId) {
        var registration = fleet.findVehicle(tenantId, vehicleId);
        if (registration.isEmpty()) {
            SecurityEventLogger.registrar("apertura_rechazada", tenantId, null, null,
                    "rechazado", Map.of("motivo", "vehiculo_desconocido_fleet"));
            throw new VehicleUnknownByFleetException(
                    "the vehicle is not known by fleet in this tenant: the order is not opened");
        }
        if (!registration.get().customerId().equals(expectedCustomerId)) {
            SecurityEventLogger.registrar("apertura_rechazada", tenantId, null, null,
                    "rechazado", Map.of("motivo", "vehiculo_de_otro_cliente"));
            throw new VehicleOwnershipMismatchException(
                    "the vehicle is not registered to the customer of the command: the order is not opened");
        }
        return registration.get();
    }
}
