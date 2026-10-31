package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.TaskNotFoundException;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handles technician task execution lifecycle: assignment, starting, completing, and reopening.
 */
@Component
public class ExecuteWorkOrderTaskUseCase {

    private final WorkOrderTaskRepositoryPort taskRepository;
    private final UnitOfWork unitOfWork;

    public ExecuteWorkOrderTaskUseCase(WorkOrderTaskRepositoryPort taskRepository, UnitOfWork unitOfWork) {
        this.taskRepository = taskRepository;
        this.unitOfWork = unitOfWork;
    }

    public WorkOrderTask assignTechnician(UUID tenantId, UUID taskId, UUID technicianId) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
            task.assignTechnician(technicianId);
            return taskRepository.save(task);
        });
    }

    public WorkOrderTask startTask(UUID tenantId, UUID taskId) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
            task.start();
            return taskRepository.save(task);
        });
    }

    public WorkOrderTask completeTask(UUID tenantId, UUID taskId) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
            task.complete();
            return taskRepository.save(task);
        });
    }

    public WorkOrderTask reopenTask(UUID tenantId, UUID taskId) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
            task.reopen();
            return taskRepository.save(task);
        });
    }
}
