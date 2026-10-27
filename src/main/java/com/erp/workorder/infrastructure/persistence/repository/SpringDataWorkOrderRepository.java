package com.erp.workorder.infrastructure.persistence.repository;

import com.erp.workorder.infrastructure.persistence.entity.WorkOrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Spring Data face of the order read view. Every query is tenant-bound:
 * the RLS policy arbitrates the rows anyway, and the explicit predicate is
 * the second barrier.
 */
public interface SpringDataWorkOrderRepository extends JpaRepository<WorkOrderJpaEntity, UUID> {

    @Query("SELECT o FROM WorkOrderJpaEntity o LEFT JOIN FETCH o.items "
            + "WHERE o.id = :orderId AND o.tenantId = :tenantId")
    Optional<WorkOrderJpaEntity> findByIdAndTenantId(@Param("orderId") UUID orderId,
                                                     @Param("tenantId") UUID tenantId);

    @Query("SELECT o FROM WorkOrderJpaEntity o LEFT JOIN FETCH o.items "
            + "WHERE o.tenantId = :tenantId ORDER BY o.openedAt DESC")
    List<WorkOrderJpaEntity> findAllByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT o FROM WorkOrderJpaEntity o LEFT JOIN FETCH o.items "
            + "WHERE o.tenantId = :tenantId AND o.status = :status ORDER BY o.openedAt DESC")
    List<WorkOrderJpaEntity> findAllByTenantIdAndStatus(@Param("tenantId") UUID tenantId,
                                                        @Param("status") String status);
}
