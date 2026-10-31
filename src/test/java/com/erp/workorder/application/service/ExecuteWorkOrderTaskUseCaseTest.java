package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.TaskNotFoundException;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TaskStatus;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteWorkOrderTaskUseCaseTest {

    private final UnitOfWork directUnitOfWork = new UnitOfWork() {
        @Override
        public void runInTransaction(Runnable work) { work.run(); }
        @Override
        public <T> T callInTransaction(Supplier<T> work) { return work.get(); }
    };

    private InMemoryTaskRepository taskRepo;
    private ExecuteWorkOrderTaskUseCase useCase;

    private UUID tenantId;
    private UUID workOrderId;
    private WorkOrderTask task;

    @BeforeEach
    void setUp() {
        taskRepo = new InMemoryTaskRepository();
        useCase = new ExecuteWorkOrderTaskUseCase(taskRepo, directUnitOfWork);

        tenantId = UUID.randomUUID();
        workOrderId = UUID.randomUUID();

        task = WorkOrderTask.create(
                new TenantId(tenantId),
                workOrderId,
                ServiceId.generate(),
                "Perform full brake inspection",
                List.of()
        );
        taskRepo.save(task);
    }

    @Test
    void assignsTechnicianSuccessfully() {
        UUID techId = UUID.randomUUID();
        WorkOrderTask updated = useCase.assignTechnician(tenantId, task.id().value(), techId);

        assertTrue(updated.assignedMechanic().isPresent());
        assertEquals(techId, updated.assignedMechanic().get().id());
    }

    @Test
    void executesCompleteLifecycleFlow() {
        UUID taskId = task.id().value();

        // 1. Start task
        WorkOrderTask doing = useCase.startTask(tenantId, taskId);
        assertEquals(TaskStatus.DOING, doing.status());
        assertTrue(doing.startedAt().isPresent());
        assertTrue(doing.completedAt().isEmpty());

        // 2. Complete task
        WorkOrderTask completed = useCase.completeTask(tenantId, taskId);
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertTrue(completed.completedAt().isPresent());

        // 3. Reopen task
        WorkOrderTask reopened = useCase.reopenTask(tenantId, taskId);
        assertEquals(TaskStatus.DOING, reopened.status());
        assertTrue(reopened.completedAt().isEmpty());
    }

    @Test
    void throwsTaskNotFoundWhenTaskDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        assertThrows(TaskNotFoundException.class, () -> useCase.startTask(tenantId, unknownId));
    }

    private static class InMemoryTaskRepository implements WorkOrderTaskRepositoryPort {
        private final Map<UUID, WorkOrderTask> store = new HashMap<>();
        @Override
        public WorkOrderTask save(WorkOrderTask task) { store.put(task.id().value(), task); return task; }
        @Override
        public Optional<WorkOrderTask> findById(TenantId tenantId, TaskId id) {
            WorkOrderTask t = store.get(id.value());
            return (t != null && t.tenantId().equals(tenantId)) ? Optional.of(t) : Optional.empty();
        }
        @Override
        public List<WorkOrderTask> findByWorkOrderId(TenantId tenantId, UUID workOrderId) { return List.of(); }
        @Override
        public void delete(TenantId tenantId, TaskId id) { store.remove(id.value()); }
    }
}
