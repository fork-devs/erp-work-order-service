package com.erp.workorder.domain.port;

import com.erp.workorder.domain.model.WorkOrderNumber;

import java.util.UUID;

/**
 * The per-tenant correlated number, RESERVED before use (same rule ADR-002
 * fixed for fiscal documents). The implementation claims the tenant row in
 * the same transaction the order opens: the counter never goes back, so a
 * cancelled order's number is never reused.
 */
public interface WorkOrderSequencePort {

    /**
     * Claims the tenant's counter row if missing ON CONFLICT DO NOTHING,
     * advances it with FOR UPDATE, and returns the number taken — all inside
     * the caller's active transaction.
     */
    WorkOrderNumber claimNextNumber(UUID tenantId);
}
