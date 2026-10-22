package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.StatusChange;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderJpaEntity;
import com.erp.workorder.infrastructure.persistence.mapper.WorkOrderPersistenceMapper;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkOrderHistoryRepository;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkOrderRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence adapter of the order aggregate. It does NOT demarcate its
 * own transaction: it joins the one the application layer opened through
 * UnitOfWork, and the Row-Level Security aspect establishes the tenant
 * context before any of its methods runs.
 *
 * <p>save() writes the order row, its items and ALL its history (an opening
 * save); updateStatus() writes the order row fields plus ONLY the pending
 * history entries the aggregate tracks, so a repeated call never duplicates
 * a history row.
 */
@Component
public class WorkOrderPersistenceAdapter implements WorkOrderRepositoryPort {

    private final SpringDataWorkOrderRepository orders;
    private final SpringDataWorkOrderHistoryRepository history;

    public WorkOrderPersistenceAdapter(SpringDataWorkOrderRepository orders,
                                       SpringDataWorkOrderHistoryRepository history) {
        this.orders = orders;
        this.history = history;
    }

    @Override
    public void save(WorkOrder order) {
        WorkOrderJpaEntity entity = WorkOrderPersistenceMapper.toEntityWithoutHistory(order);
        orders.save(entity);
        history.saveAll(WorkOrderPersistenceMapper.toHistoryEntities(order, order.history()));
        order.markHistoryPersisted();
    }

    @Override
    public Optional<WorkOrder> findById(UUID tenantId, UUID orderId) {
        return orders.findByIdAndTenantId(orderId, tenantId)
                .map(entity -> WorkOrderPersistenceMapper.toDomain(entity,
                        history.findAllByOrderIdAndTenant(orderId, tenantId)));
    }

    @Override
    public List<WorkOrder> listByTenant(UUID tenantId, WorkOrderStatus status) {
        List<WorkOrderJpaEntity> rows = status == null
                ? orders.findAllByTenantId(tenantId)
                : orders.findAllByTenantIdAndStatus(tenantId, status.name());
        return rows.stream()
                .map(entity -> WorkOrderPersistenceMapper.toDomain(entity,
                        history.findAllByOrderIdAndTenant(entity.getId(), tenantId)))
                .toList();
    }

    @Override
    public void updateStatus(WorkOrder order) {
        orders.findByIdAndTenantId(order.id(), order.tenantId().id()).ifPresent(entity -> {
            entity.setStatus(order.status().name());
            entity.setDiagnosis(order.diagnosis());
            entity.setMechanicId(order.mechanic().map(MechanicRef::id).orElse(null));
            entity.setClosedAt(order.closedAt().orElse(null));
            orders.save(entity);
            List<StatusChange> pending = order.unpersistedHistory();
            if (!pending.isEmpty()) {
                history.saveAll(WorkOrderPersistenceMapper.toHistoryEntities(order, pending));
            }
            order.markHistoryPersisted();
        });
    }
}
