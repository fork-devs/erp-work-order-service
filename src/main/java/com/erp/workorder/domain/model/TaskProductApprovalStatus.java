package com.erp.workorder.domain.model;

/**
 * Approval lifecycle for spare parts allocated to a work order task.
 */
public enum TaskProductApprovalStatus {
    PENDING_SUPERVISOR_CHECK,
    SENT_TO_CUSTOMER,
    CUSTOMER_APPROVED,
    CUSTOMER_REJECTED
}
