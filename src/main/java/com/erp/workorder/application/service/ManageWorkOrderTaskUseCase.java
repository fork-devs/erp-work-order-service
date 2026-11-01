package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.ServiceNotFoundException;
import com.erp.workorder.application.exception.TaskNotFoundException;
import com.erp.workorder.application.exception.WorkOrderClosedException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Handles creation, updates, and deletion of work order tasks.
 */
@Component
public class ManageWorkOrderTaskUseCase {

    private final WorkOrderRepositoryPort workOrderRepository;
    private final WorkOrderTaskRepositoryPort taskRepository;
    private final WorkshopServiceRepositoryPort serviceRepository;
    private final InventoryPort inventoryPort;
    private final UnitOfWork unitOfWork;

    public ManageWorkOrderTaskUseCase(WorkOrderRepositoryPort workOrderRepository,
                                      WorkOrderTaskRepositoryPort taskRepository,
                                      WorkshopServiceRepositoryPort serviceRepository,
                                      InventoryPort inventoryPort,
                                      UnitOfWork unitOfWork) {
        this.workOrderRepository = workOrderRepository;
        this.taskRepository = taskRepository;
        this.serviceRepository = serviceRepository;
        this.inventoryPort = inventoryPort;
        this.unitOfWork = unitOfWork;
    }

    public WorkOrderTask addTask(UUID tenantId,
                                 UUID workOrderId,
                                 UUID serviceId,
                                 String description,
                                 List<String> evidenceImages) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrder order = workOrderRepository.findById(tenantId, workOrderId)
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + workOrderId));

            if (isClosed(order.status())) {
                throw new WorkOrderClosedException("Cannot add tasks to a closed work order in status: " + order.status());
            }

            ServiceId sId = ServiceId.of(serviceId);
            serviceRepository.findById(tId, sId)
                    .orElseThrow(() -> new ServiceNotFoundException("Service not found: " + serviceId));

            WorkOrderTask task = WorkOrderTask.create(tId, workOrderId, sId, description, evidenceImages);
            return taskRepository.save(task);
        });
    }

    public WorkOrderTask updateTask(UUID tenantId,
                                    UUID workOrderId,
                                    UUID taskId,
                                    UUID serviceId,
                                    UUID assignedMechanicId,
                                    String description,
                                    List<String> evidenceImages) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .filter(t -> t.workOrderId().equals(workOrderId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId + " for order: " + workOrderId));

            ServiceId sId = null;
            if (serviceId != null) {
                sId = ServiceId.of(serviceId);
                serviceRepository.findById(tId, sId)
                        .orElseThrow(() -> new ServiceNotFoundException("Service not found: " + serviceId));
            }

            task.updateDetails(sId, assignedMechanicId, description, evidenceImages);
            return taskRepository.save(task);
        });
    }

    public void deleteTask(UUID tenantId, UUID workOrderId, UUID taskId) {
        unitOfWork.runInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrder order = workOrderRepository.findById(tenantId, workOrderId)
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + workOrderId));

            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .filter(t -> t.workOrderId().equals(workOrderId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            // Release reservations for any products allocated to this task
            for (TaskProduct product : task.products()) {
                String idempotencyKey = "wo-task-" + taskId + "-prod-" + product.productId() + "-rel";
                inventoryPort.release(new InventoryPort.StockReservation(
                        product.productId(),
                        order.branch().id(),
                        product.quantity(),
                        workOrderId,
                        idempotencyKey
                ));
            }

            taskRepository.delete(tId, task.id());
        });
    }

    private boolean isClosed(WorkOrderStatus status) {
        return status == WorkOrderStatus.ENTREGADA
                || status == WorkOrderStatus.RECHAZADA
                || status == WorkOrderStatus.CANCELADA;
    }
}
