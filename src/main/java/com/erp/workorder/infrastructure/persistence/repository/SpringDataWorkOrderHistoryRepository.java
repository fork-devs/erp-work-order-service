package com.erp.workorder.infrastructure.persistence.repository;

import com.erp.workorder.infrastructure.persistence.entity.WorkOrderStatusHistoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The Spring Data face of the immutable history. The rows are written once
 * and never modified; the reads of one order always get its history in time
 * order.
 */
public interface SpringDataWorkOrderHistoryRepository extends JpaRepository<WorkOrderStatusHistoryJpaEntity, UUID> {

    @Query("SELECT h FROM WorkOrderStatusHistoryJpaEntity h "
            + "WHERE h.workOrderId = :orderId AND h.tenantId = :tenantId ORDER BY h.changedAt ASC")
    List<WorkOrderStatusHistoryJpaEntity> findAllByOrderIdAndTenant(@Param("orderId") UUID orderId,
                                                                    @Param("tenantId") UUID tenantId);
}
