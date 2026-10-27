package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.FleetUnavailableException;
import com.erp.workorder.application.exception.VehicleOwnershipMismatchException;
import com.erp.workorder.application.exception.VehicleUnknownByFleetException;
import com.erp.workorder.domain.port.FleetPort;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The fail-closed verification the order opening depends on: an unknown
 * vehicle, a vehicle of ANOTHER customer and a fleet outage are all
 * rejections, and none of them may leave the opening proceed.
 */
class VehicleVerificationServiceTest {

    private final FakeFleetPort fleet = new FakeFleetPort();
    private final VehicleVerificationService verification = new VehicleVerificationService(fleet);

    private final UUID tenant = UUID.randomUUID();
    private final UUID vehicle = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void cleanTheFake() {
        fleet.reset();
        fleet.registerVehicle(tenant, vehicle, owner);
    }

    @Test
    void theVehicleOfTheRealOwnerIsVerified() {
        FleetPort.VehicleRegistration registration =
                verification.verifyOpening(tenant, vehicle, owner);

        assertEquals(owner, registration.customerId());
    }

    @Test
    void aVehicleRegisteredToAnotherCustomerIsRejected() {
        assertThrows(VehicleOwnershipMismatchException.class,
                () -> verification.verifyOpening(tenant, vehicle, UUID.randomUUID()));
    }

    @Test
    void aVehicleTheFleetDoesNotKnowIsRejected() {
        fleet.reset();

        assertThrows(VehicleUnknownByFleetException.class,
                () -> verification.verifyOpening(tenant, vehicle, owner));
    }

    @Test
    void anOutagedFleetPropagatesItsAvailabilityFailure() {
        fleet.failWith(new FleetUnavailableException("the fleet did not answer within the deadline"));

        FleetUnavailableException failure = assertThrows(FleetUnavailableException.class,
                () -> verification.verifyOpening(tenant, vehicle, owner));

        assertEquals("the fleet did not answer within the deadline", failure.getMessage());
    }
}
