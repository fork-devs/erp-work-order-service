package com.erp.workorder.application.exception;

/**
 * The vehicle EXISTS in fleet but its registered customer is not the
 * customer of the order. The opening is rejected and the order is not
 * created: the payload does not decide the ownership, fleet does.
 */
public class VehicleOwnershipMismatchException extends RuntimeException {

    public VehicleOwnershipMismatchException(String message) {
        super(message);
    }
}
