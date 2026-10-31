package com.erp.workorder.domain.port;

import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrderTask;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for managing work order tasks and their products.
 * Zero framework dependencies.
 */
public interface WorkOrderTaskRepositoryPort {

    WorkOrderTask save(WorkOrderTask task);

    Optional<WorkOrderTask> findById(TenantId tenantId, TaskId id);

    List<WorkOrderTask> findByWorkOrderId(TenantId tenantId, UUID workOrderId);

    void delete(TenantId tenantId, TaskId id);
}
