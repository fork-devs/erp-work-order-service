package com.erp.workorder.application.exception;

public class WorkOrderClosedException extends RuntimeException {
    public WorkOrderClosedException(String message) {
        super(message);
    }
}
