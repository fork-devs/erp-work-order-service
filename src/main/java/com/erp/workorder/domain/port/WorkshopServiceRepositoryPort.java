package com.erp.workorder.domain.port;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;

import java.util.List;
import java.util.Optional;

/**
 * Output port for managing workshop service catalog persistence.
 * Zero framework dependencies.
 */
public interface WorkshopServiceRepositoryPort {

    WorkshopService save(WorkshopService service);

    Optional<WorkshopService> findById(TenantId tenantId, ServiceId id);

    List<WorkshopService> findByBranch(TenantId tenantId, BranchId branchId);

    void delete(TenantId tenantId, ServiceId id);

    boolean hasActiveTaskReferences(TenantId tenantId, ServiceId id);
}
