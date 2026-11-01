package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.ServiceDeletionConflictException;
import com.erp.workorder.application.exception.ServiceNotFoundException;
import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkshopServiceUseCaseTest {

    private final UnitOfWork directUnitOfWork = new UnitOfWork() {
        @Override
        public void runInTransaction(Runnable work) {
            work.run();
        }

        @Override
        public <T> T callInTransaction(Supplier<T> work) {
            return work.get();
        }
    };

    private InMemoryWorkshopServiceRepository repository;
    private WorkshopServiceUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWorkshopServiceRepository();
        useCase = new WorkshopServiceUseCase(repository, directUnitOfWork);
    }

    @Test
    void createsAndRetrievesService() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        WorkshopService created = useCase.create(tenantId, branchId, "Brake Fluid Flush", new BigDecimal("45.00"));

        assertNotNull(created.id());
        assertEquals("Brake Fluid Flush", created.name());
        assertEquals(new BigDecimal("45.00"), created.price());

        WorkshopService retrieved = useCase.getById(tenantId, created.id().value());
        assertEquals(created.id(), retrieved.id());
        assertEquals("Brake Fluid Flush", retrieved.name());
    }

    @Test
    void listsServicesByBranch() {
        UUID tenantId = UUID.randomUUID();
        UUID branch1 = UUID.randomUUID();
        UUID branch2 = UUID.randomUUID();

        useCase.create(tenantId, branch1, "Service A", new BigDecimal("10.00"));
        useCase.create(tenantId, branch1, "Service B", new BigDecimal("20.00"));
        useCase.create(tenantId, branch2, "Service C", new BigDecimal("30.00"));

        List<WorkshopService> branch1Services = useCase.listByBranch(tenantId, branch1);
        assertEquals(2, branch1Services.size());

        List<WorkshopService> branch2Services = useCase.listByBranch(tenantId, branch2);
        assertEquals(1, branch2Services.size());
    }

    @Test
    void updatesServiceDetails() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        WorkshopService created = useCase.create(tenantId, branchId, "Tire Rotation", new BigDecimal("30.00"));

        WorkshopService updated = useCase.update(tenantId, created.id().value(), "Tire Rotation & Balance", new BigDecimal("40.00"));

        assertEquals("Tire Rotation & Balance", updated.name());
        assertEquals(new BigDecimal("40.00"), updated.price());
    }

    @Test
    void deletesServiceWhenNotReferenced() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        WorkshopService created = useCase.create(tenantId, branchId, "Coolant Flush", new BigDecimal("55.00"));
        UUID serviceId = created.id().value();

        useCase.delete(tenantId, serviceId);

        assertThrows(ServiceNotFoundException.class, () -> useCase.getById(tenantId, serviceId));
    }

    @Test
    void rejectsDeletionWhenServiceIsReferencedInTasks() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        WorkshopService created = useCase.create(tenantId, branchId, "Oil Change", new BigDecimal("35.00"));
        UUID serviceId = created.id().value();

        repository.markAsReferenced(ServiceId.of(serviceId));

        assertThrows(ServiceDeletionConflictException.class, () -> useCase.delete(tenantId, serviceId));
    }

    private static class InMemoryWorkshopServiceRepository implements WorkshopServiceRepositoryPort {
        private final Map<UUID, WorkshopService> store = new HashMap<>();
        private final Set<UUID> referencedServices = new HashSet<>();

        void markAsReferenced(ServiceId id) {
            referencedServices.add(id.value());
        }

        @Override
        public WorkshopService save(WorkshopService service) {
            store.put(service.id().value(), service);
            return service;
        }

        @Override
        public Optional<WorkshopService> findById(TenantId tenantId, ServiceId id) {
            WorkshopService s = store.get(id.value());
            if (s != null && s.tenantId().equals(tenantId)) {
                return Optional.of(s);
            }
            return Optional.empty();
        }

        @Override
        public List<WorkshopService> findByBranch(TenantId tenantId, BranchId branchId) {
            List<WorkshopService> list = new ArrayList<>();
            for (WorkshopService s : store.values()) {
                if (s.tenantId().equals(tenantId) && s.branchId().equals(branchId)) {
                    list.add(s);
                }
            }
            return list;
        }

        @Override
        public void delete(TenantId tenantId, ServiceId id) {
            WorkshopService s = store.get(id.value());
            if (s != null && s.tenantId().equals(tenantId)) {
                store.remove(id.value());
            }
        }

        @Override
        public boolean hasActiveTaskReferences(TenantId tenantId, ServiceId id) {
            return referencedServices.contains(id.value());
        }
    }
}
