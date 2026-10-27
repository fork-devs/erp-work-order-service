package com.erp.workorder.domain.port;

import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.model.WorkOrderStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence contract of the order aggregate. The adapter joins the
 * transaction opened by the UnitOfWork port: it never demarcates its own,
 * never opens a second one, and rehydrates/saves the aggregate and its
 * history exactly as the domain models them.
 *
 * <p>EVERY method takes the tenant identifier: the RLS policy arbitrates the
 * rows anyway, but passing it explicitly is the second barrier and makes the
 * database context requirement visible at every call.
 */
public interface WorkOrderRepositoryPort {

    /** Saves a new aggregate: the order row, its items and its opening history entry, same transaction. */
    void save(WorkOrder order);

    /** The order as the domain models it: status and history exactly as stored, or empty when absent. */
    Optional<WorkOrder> findById(UUID tenantId, UUID orderId);

    /** The orders of the tenant, newest last, with their items (the workshop list view). */
    List<WorkOrder> listByTenant(UUID tenantId, WorkOrderStatus status);

    /** Persists a transition that already went through the aggregate (status, history, closure). */
    void updateStatus(WorkOrder order);
}
