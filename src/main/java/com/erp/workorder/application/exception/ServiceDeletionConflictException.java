package com.erp.workorder.application.exception;

public class ServiceDeletionConflictException extends RuntimeException {
    public ServiceDeletionConflictException(String message) {
        super(message);
    }
}
