package com.erp.workorder.infrastructure.persistence.mapper;

import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.TaskProductId;
import com.erp.workorder.domain.model.TaskStatus;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderTaskJpaEntity;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderTaskProductJpaEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WorkOrderTaskPersistenceMapper {

    private WorkOrderTaskPersistenceMapper() {}

    public static WorkOrderTaskJpaEntity toEntity(WorkOrderTask task) {
        String[] images = task.evidenceImages().toArray(new String[0]);
        WorkOrderTaskJpaEntity entity = new WorkOrderTaskJpaEntity(
                task.id().value(),
                task.tenantId().id(),
                task.workOrderId(),
                task.serviceId().value(),
                task.assignedMechanic().map(MechanicRef::id).orElse(null),
                task.description(),
                task.status().name(),
                images,
                task.startedAt().orElse(null),
                task.completedAt().orElse(null),
                task.createdAt(),
                task.updatedAt()
        );

        List<WorkOrderTaskProductJpaEntity> productEntities = new ArrayList<>();
        for (TaskProduct product : task.products()) {
            productEntities.add(new WorkOrderTaskProductJpaEntity(
                    product.id().value(),
                    product.tenantId().id(),
                    entity,
                    product.productId(),
                    product.quantity(),
                    product.unitPrice(),
                    product.totalAmount(),
                    product.createdAt(),
                    product.updatedAt()
            ));
        }
        entity.setProducts(productEntities);
        return entity;
    }

    public static WorkOrderTask toDomain(WorkOrderTaskJpaEntity entity) {
        List<String> images = entity.getEvidenceImages() != null
                ? Arrays.asList(entity.getEvidenceImages())
                : List.of();

        List<TaskProduct> products = new ArrayList<>();
        if (entity.getProducts() != null) {
            for (WorkOrderTaskProductJpaEntity p : entity.getProducts()) {
                products.add(new TaskProduct(
                        TaskProductId.of(p.getId()),
                        new TenantId(p.getTenantId()),
                        TaskId.of(entity.getId()),
                        p.getProductId(),
                        p.getQuantity(),
                        p.getUnitPrice(),
                        p.getCreatedAt(),
                        p.getUpdatedAt()
                ));
            }
        }

        MechanicRef mechanic = entity.getAssignedMechanicId() != null
                ? new MechanicRef(entity.getAssignedMechanicId())
                : null;

        return new WorkOrderTask(
                TaskId.of(entity.getId()),
                new TenantId(entity.getTenantId()),
                entity.getWorkOrderId(),
                ServiceId.of(entity.getServiceId()),
                mechanic,
                entity.getDescription(),
                TaskStatus.valueOf(entity.getStatus()),
                images,
                entity.getStartedAt(),
                entity.getCompletedAt(),
                products,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
