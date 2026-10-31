package com.erp.workorder.application.exception;

public class TaskProductNotFoundException extends RuntimeException {
    public TaskProductNotFoundException(String message) {
        super(message);
    }
}
