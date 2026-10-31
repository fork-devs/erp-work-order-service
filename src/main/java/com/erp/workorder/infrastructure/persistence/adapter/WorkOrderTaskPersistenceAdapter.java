package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderTaskJpaEntity;
import com.erp.workorder.infrastructure.persistence.mapper.WorkOrderTaskPersistenceMapper;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkOrderTaskRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkOrderTaskPersistenceAdapter implements WorkOrderTaskRepositoryPort {

    private final SpringDataWorkOrderTaskRepository repository;

    public WorkOrderTaskPersistenceAdapter(SpringDataWorkOrderTaskRepository repository) {
        this.repository = repository;
    }

    @Override
    public WorkOrderTask save(WorkOrderTask task) {
        WorkOrderTaskJpaEntity entity = WorkOrderTaskPersistenceMapper.toEntity(task);
        WorkOrderTaskJpaEntity saved = repository.save(entity);
        return WorkOrderTaskPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<WorkOrderTask> findById(TenantId tenantId, TaskId id) {
        return repository.findByTenantIdAndId(tenantId.id(), id.value())
                .map(WorkOrderTaskPersistenceMapper::toDomain);
    }

    @Override
    public List<WorkOrderTask> findByWorkOrderId(TenantId tenantId, UUID workOrderId) {
        return repository.findByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId.id(), workOrderId)
                .stream()
                .map(WorkOrderTaskPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void delete(TenantId tenantId, TaskId id) {
        repository.deleteByTenantIdAndId(tenantId.id(), id.value());
    }
}
