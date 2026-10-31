package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.ServiceDeletionConflictException;
import com.erp.workorder.application.exception.ServiceNotFoundException;
import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Manages the workshop service catalog operations with tenant isolation and branch scoping.
 */
@Component
public class WorkshopServiceUseCase {

    private final WorkshopServiceRepositoryPort repository;
    private final UnitOfWork unitOfWork;

    public WorkshopServiceUseCase(WorkshopServiceRepositoryPort repository, UnitOfWork unitOfWork) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
    }

    public WorkshopService create(UUID tenantId, UUID branchId, String name, BigDecimal price) {
        return unitOfWork.callInTransaction(() -> {
            WorkshopService service = WorkshopService.create(
                    new TenantId(tenantId),
                    new BranchId(branchId),
                    name,
                    price
            );
            return repository.save(service);
        });
    }

    public List<WorkshopService> listByBranch(UUID tenantId, UUID branchId) {
        return unitOfWork.callInTransaction(() ->
                repository.findByBranch(new TenantId(tenantId), new BranchId(branchId)));
    }

    public WorkshopService getById(UUID tenantId, UUID serviceId) {
        return unitOfWork.callInTransaction(() ->
                repository.findById(new TenantId(tenantId), ServiceId.of(serviceId))
                        .orElseThrow(() -> new ServiceNotFoundException("Service not found: " + serviceId)));
    }

    public WorkshopService update(UUID tenantId, UUID serviceId, String name, BigDecimal price) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            ServiceId sId = ServiceId.of(serviceId);
            WorkshopService service = repository.findById(tId, sId)
                    .orElseThrow(() -> new ServiceNotFoundException("Service not found: " + serviceId));
            service.update(name, price);
            return repository.save(service);
        });
    }

    public void delete(UUID tenantId, UUID serviceId) {
        unitOfWork.runInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            ServiceId sId = ServiceId.of(serviceId);
            repository.findById(tId, sId)
                    .orElseThrow(() -> new ServiceNotFoundException("Service not found: " + serviceId));

            if (repository.hasActiveTaskReferences(tId, sId)) {
                throw new ServiceDeletionConflictException(
                        "Cannot delete service " + serviceId + " as it is referenced in work order tasks");
            }
            repository.delete(tId, sId);
        });
    }
}
