package com.erp.workorder.infrastructure.persistence.repository;

import com.erp.workorder.infrastructure.persistence.entity.WorkOrderEvidenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataWorkOrderEvidenceRepository extends JpaRepository<WorkOrderEvidenceJpaEntity, UUID> {

    List<WorkOrderEvidenceJpaEntity> findByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(UUID tenantId, UUID workOrderId);
}
