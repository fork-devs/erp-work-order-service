package com.erp.workorder.infrastructure.persistence.repository;

import com.erp.workorder.infrastructure.persistence.entity.WorkshopServiceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataWorkshopServiceRepository extends JpaRepository<WorkshopServiceJpaEntity, UUID> {

    Optional<WorkshopServiceJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<WorkshopServiceJpaEntity> findByTenantIdAndBranchIdOrderByNameAsc(UUID tenantId, UUID branchId);

    void deleteByTenantIdAndId(UUID tenantId, UUID id);

    @Query(value = "SELECT COUNT(1) > 0 FROM work_order_tasks WHERE tenant_id = :tenantId AND service_id = :serviceId", nativeQuery = true)
    boolean existsTaskReferencingService(@Param("tenantId") UUID tenantId, @Param("serviceId") UUID serviceId);
}
