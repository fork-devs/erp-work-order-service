package com.erp.workorder.support;

import com.erp.workorder.domain.port.FleetPort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A fake fleet registered IN THE TEST CONTEXT while the HTTP client of
 * group 6 does not exist yet: the opening use case asks for a vehicle, and
 * the tests steer what the fake answers. The real client takes over in
 * task 6.3 by implementing the same port.
 */
@Component
public class FakeFleetPort implements FleetPort {

    private final Map<VehicleKey, UUID> owners = new HashMap<>();

    @Override
    public Optional<VehicleRegistration> findVehicle(UUID tenantId, UUID vehicleId) {
        UUID customerId = owners.get(new VehicleKey(tenantId, vehicleId));
        if (customerId == null) {
            return Optional.empty();
        }
        return Optional.of(new VehicleRegistration(vehicleId, customerId));
    }

    /** Registers a vehicle for the tenant, owned by the given customer. */
    public void registerVehicle(UUID tenantId, UUID vehicleId, UUID customerId) {
        owners.put(new VehicleKey(tenantId, vehicleId), customerId);
    }

    public void reset() {
        owners.clear();
    }

    private record VehicleKey(UUID tenantId, UUID vehicleId) {
    }
}
