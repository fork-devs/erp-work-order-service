package com.erp.workorder.infrastructure.persistence.repository;

import com.erp.workorder.infrastructure.persistence.entity.WorkOrderTaskJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataWorkOrderTaskRepository extends JpaRepository<WorkOrderTaskJpaEntity, UUID> {

    Optional<WorkOrderTaskJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<WorkOrderTaskJpaEntity> findByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(UUID tenantId, UUID workOrderId);

    void deleteByTenantIdAndId(UUID tenantId, UUID id);
}
