package com.erp.workorder.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The fleet verification the order opening needs: the vehicle exists and its
 * registered customer is the customer of the order. Implemented by an HTTP
 * client adapter with the documented internal-call guarantees (deadline, one
 * retry, circuit breaker); the domain defines only the question and the
 * answer, never the transport details.
 */
public interface FleetPort {

    /** The vehicle of the tenant, with the customer fleet registered it against, or empty when unknown. */
    Optional<VehicleRegistration> findVehicle(UUID tenantId, UUID vehicleId);

    record VehicleRegistration(UUID vehicleId, UUID customerId) {
    }
}
