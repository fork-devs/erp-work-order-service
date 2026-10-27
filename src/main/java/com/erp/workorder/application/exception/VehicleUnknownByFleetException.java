package com.erp.workorder.application.exception;

/**
 * The customer or the vehicle of the opening payload is not known by fleet.
 * The order does not come to exist; the rejection must not be reported as a
 * data conflict of the tenant.
 */
public class VehicleUnknownByFleetException extends RuntimeException {

    public VehicleUnknownByFleetException(String message) {
        super(message);
    }
}
