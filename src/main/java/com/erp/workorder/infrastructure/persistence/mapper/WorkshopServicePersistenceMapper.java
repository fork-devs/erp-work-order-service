package com.erp.workorder.infrastructure.persistence.mapper;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.infrastructure.persistence.entity.WorkshopServiceJpaEntity;

public final class WorkshopServicePersistenceMapper {

    private WorkshopServicePersistenceMapper() {}

    public static WorkshopServiceJpaEntity toEntity(WorkshopService service) {
        return new WorkshopServiceJpaEntity(
                service.id().value(),
                service.tenantId().id(),
                service.branchId().id(),
                service.name(),
                service.price(),
                service.createdAt(),
                service.updatedAt()
        );
    }

    public static WorkshopService toDomain(WorkshopServiceJpaEntity entity) {
        return new WorkshopService(
                ServiceId.of(entity.getId()),
                new TenantId(entity.getTenantId()),
                new BranchId(entity.getBranchId()),
                entity.getName(),
                entity.getPrice(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
