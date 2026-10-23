package com.erp.workorder.support;

import com.erp.workorder.application.exception.FleetUnavailableException;
import com.erp.workorder.domain.port.FleetPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A fake fleet registered IN THE TEST CONTEXT. The real HTTP client
 * ({@code FleetClient}) also implements the port; this fake is
 * {@code @Primary} so the tests steer fleet without a wire — the primary
 * mark lives in test support, which is not on the classpath of the
 * packaged service, so production always resolves the real client.
 *
 * <p>The fake can also simulate an outage with
 * {@link #failWith(FleetUnavailableException)}: the verification fails
 * closed exactly as with a dead fleet.
 */
@Component
@Primary
public class FakeFleetPort implements FleetPort {

    private final Map<VehicleKey, UUID> owners = new HashMap<>();
    private FleetUnavailableException outage;

    @Override
    public Optional<VehicleRegistration> findVehicle(UUID tenantId, UUID vehicleId) {
        if (outage != null) {
            throw outage;
        }
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

    /** Puts the fake fleet down: every verification fails with this exception. */
    public void failWith(FleetUnavailableException outage) {
        this.outage = outage;
    }

    public void reset() {
        owners.clear();
        outage = null;
    }

    private record VehicleKey(UUID tenantId, UUID vehicleId) {
    }
}
