package com.erp.workorder.infrastructure.persistence.mapper;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.SparePartRef;
import com.erp.workorder.domain.model.StatusChange;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.VehicleRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderItem;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderItemJpaEntity;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderJpaEntity;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderStatusHistoryJpaEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Translates the domain aggregate to the persistence read views and back.
 * The aggregate never carries JPA, the entities never carry the state
 * machine: each side stays in its layer.
 */
public final class WorkOrderPersistenceMapper {

    private WorkOrderPersistenceMapper() {
    }

    public static WorkOrderJpaEntity toEntityWithoutHistory(WorkOrder order) {
        WorkOrderJpaEntity entity = new WorkOrderJpaEntity();
        entity.setId(order.id());
        entity.setTenantId(order.tenantId().id());
        entity.setNumber(order.number().value());
        entity.setCustomerId(order.customer().id());
        entity.setVehicleId(order.vehicle().id());
        entity.setBranchId(order.branch().id());
        entity.setMechanicId(order.mechanic().map(MechanicRef::id).orElse(null));
        entity.setStatus(order.status().name());
        entity.setDiagnosis(order.diagnosis());
        entity.setOpenedAt(order.openedAt());
        entity.setClosedAt(order.closedAt().orElse(null));
        for (WorkOrderItem item : order.items()) {
            entity.getItems().add(toEntity(entity, item));
        }
        return entity;
    }

    private static WorkOrderItemJpaEntity toEntity(WorkOrderJpaEntity order, WorkOrderItem item) {
        WorkOrderItemJpaEntity entity = new WorkOrderItemJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(order.getTenantId());
        entity.setOrder(order);
        entity.setSparePartId(item.sparePart().id());
        entity.setQuantity(item.quantity());
        entity.setLaborPrice(item.laborPrice());
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    /** The full item collection of the aggregate, ready to replace the stored one. */
    public static List<WorkOrderItemJpaEntity> toItemEntities(WorkOrderJpaEntity order,
                                                              List<WorkOrderItem> items) {
        List<WorkOrderItemJpaEntity> rows = new ArrayList<>();
        for (WorkOrderItem item : items) {
            rows.add(toEntity(order, item));
        }
        return rows;
    }

    /**
     * The history rows of the order: the opening entry carries a null origin
     * (no previous status), every transition carries origin and destination.
     */
    public static List<WorkOrderStatusHistoryJpaEntity> toHistoryEntities(WorkOrder order,
                                                                          List<StatusChange> entries) {
        List<WorkOrderStatusHistoryJpaEntity> rows = new ArrayList<>();
        for (StatusChange entry : entries) {
            WorkOrderStatusHistoryJpaEntity row = new WorkOrderStatusHistoryJpaEntity();
            row.setId(UUID.randomUUID());
            row.setTenantId(order.tenantId().id());
            row.setWorkOrderId(order.id());
            row.setFromStatus(entry.transition() == null ? null : entry.transition().origin().name());
            row.setToStatus(entry.transition() == null ? WorkOrderStatus.RECEPCIONADA.name()
                    : entry.transition().destination().name());
            row.setChangedBy(entry.changedBy());
            row.setChangedAt(entry.changedAt());
            rows.add(row);
        }
        return rows;
    }

    public static WorkOrder toDomain(WorkOrderJpaEntity entity, List<WorkOrderStatusHistoryJpaEntity> historyRows) {
        return WorkOrder.rehydrated(
                entity.getId(),
                new TenantId(entity.getTenantId()),
                WorkOrderNumber.of(numberSequence(entity.getNumber())),
                new CustomerRef(entity.getCustomerId()),
                new VehicleRef(entity.getVehicleId()),
                new BranchId(entity.getBranchId()),
                entity.getMechanicId() == null ? null : new MechanicRef(entity.getMechanicId()),
                WorkOrderStatus.valueOf(entity.getStatus()),
                entity.getDiagnosis(),
                entity.getOpenedAt(),
                entity.getClosedAt(),
                toDomainItems(entity),
                toDomainHistory(historyRows)
        );
    }

    private static List<WorkOrderItem> toDomainItems(WorkOrderJpaEntity entity) {
        List<WorkOrderItem> items = new ArrayList<>();
        for (WorkOrderItemJpaEntity row : entity.getItems()) {
            items.add(new WorkOrderItem(
                    new SparePartRef(row.getSparePartId()),
                    row.getQuantity(),
                    row.getLaborPrice() == null ? BigDecimal.ZERO : row.getLaborPrice()));
        }
        return items;
    }

    private static List<StatusChange> toDomainHistory(List<WorkOrderStatusHistoryJpaEntity> historyRows) {
        List<StatusChange> entries = new ArrayList<>();
        for (WorkOrderStatusHistoryJpaEntity row : historyRows) {
            WorkOrderStatus.Transition transition = row.getFromStatus() == null ? null
                    : new WorkOrderStatus.Transition(
                            WorkOrderStatus.valueOf(row.getFromStatus()),
                            WorkOrderStatus.valueOf(row.getToStatus()));
            entries.add(new StatusChange(
                    transition,
                    row.getChangedBy(),
                    row.getChangedAt()));
        }
        return entries;
    }

    /** The sequence back from its legible {@code WO-000417} form. */
    private static int numberSequence(String number) {
        return Integer.parseInt(number.substring("WO-".length()));
    }
}
