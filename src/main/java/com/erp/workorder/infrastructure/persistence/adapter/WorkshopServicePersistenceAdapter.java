package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import com.erp.workorder.infrastructure.persistence.entity.WorkshopServiceJpaEntity;
import com.erp.workorder.infrastructure.persistence.mapper.WorkshopServicePersistenceMapper;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkshopServiceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class WorkshopServicePersistenceAdapter implements WorkshopServiceRepositoryPort {

    private final SpringDataWorkshopServiceRepository repository;

    public WorkshopServicePersistenceAdapter(SpringDataWorkshopServiceRepository repository) {
        this.repository = repository;
    }

    @Override
    public WorkshopService save(WorkshopService service) {
        WorkshopServiceJpaEntity entity = WorkshopServicePersistenceMapper.toEntity(service);
        WorkshopServiceJpaEntity saved = repository.save(entity);
        return WorkshopServicePersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<WorkshopService> findById(TenantId tenantId, ServiceId id) {
        return repository.findByTenantIdAndId(tenantId.id(), id.value())
                .map(WorkshopServicePersistenceMapper::toDomain);
    }

    @Override
    public List<WorkshopService> findByBranch(TenantId tenantId, BranchId branchId) {
        return repository.findByTenantIdAndBranchIdOrderByNameAsc(tenantId.id(), branchId.id())
                .stream()
                .map(WorkshopServicePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void delete(TenantId tenantId, ServiceId id) {
        repository.deleteByTenantIdAndId(tenantId.id(), id.value());
    }

    @Override
    public boolean hasActiveTaskReferences(TenantId tenantId, ServiceId id) {
        return repository.existsTaskReferencingService(tenantId.id(), id.value());
    }
}
