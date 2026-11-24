package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.TaskNotFoundException;
import com.erp.workorder.application.exception.TaskProductNotFoundException;
import com.erp.workorder.application.exception.WorkOrderClosedException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Handles spare parts allocation for work order tasks with immediate inventory stock reservations.
 */
@Component
public class TaskProductAllocationUseCase {

    private final WorkOrderRepositoryPort workOrderRepository;
    private final WorkOrderTaskRepositoryPort taskRepository;
    private final InventoryPort inventoryPort;
    private final UnitOfWork unitOfWork;

    public TaskProductAllocationUseCase(WorkOrderRepositoryPort workOrderRepository,
                                       WorkOrderTaskRepositoryPort taskRepository,
                                       InventoryPort inventoryPort,
                                       UnitOfWork unitOfWork) {
        this.workOrderRepository = workOrderRepository;
        this.taskRepository = taskRepository;
        this.inventoryPort = inventoryPort;
        this.unitOfWork = unitOfWork;
    }

    public TaskProduct addProduct(UUID tenantId, UUID taskId, UUID productId, int quantity, BigDecimal unitPrice) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            WorkOrder order = workOrderRepository.findById(tenantId, task.workOrderId())
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + task.workOrderId()));

            if (isClosed(order.status())) {
                throw new WorkOrderClosedException("Cannot allocate parts to closed work order in status: " + order.status());
            }

            // Reserve stock immediately via inventory port
            String idempotencyKey = "wo-task-" + taskId + "-prod-" + productId + "-res-" + quantity;
            inventoryPort.reserve(new InventoryPort.StockReservation(
                    productId,
                    order.branch().id(),
                    quantity,
                    order.id(),
                    idempotencyKey
            ));

            TaskProduct product = TaskProduct.create(tId, task.id(), productId, quantity, unitPrice);
            task.addProduct(product);
            taskRepository.save(task);
            return product;
        });
    }

    public TaskProduct updateProductQuantity(UUID tenantId, UUID taskId, UUID productId, int newQuantity) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            WorkOrder order = workOrderRepository.findById(tenantId, task.workOrderId())
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + task.workOrderId()));

            TaskProduct existing = task.findProduct(productId)
                    .orElseThrow(() -> new TaskProductNotFoundException("Product " + productId + " not found on task " + taskId));

            int diff = newQuantity - existing.quantity();
            if (diff > 0) {
                String idempotencyKey = "wo-task-" + taskId + "-prod-" + productId + "-adj-inc-" + newQuantity;
                inventoryPort.reserve(new InventoryPort.StockReservation(
                        productId,
                        order.branch().id(),
                        diff,
                        order.id(),
                        idempotencyKey
                ));
            } else if (diff < 0) {
                String idempotencyKey = "wo-task-" + taskId + "-prod-" + productId + "-adj-dec-" + newQuantity;
                inventoryPort.release(new InventoryPort.StockReservation(
                        productId,
                        order.branch().id(),
                        -diff,
                        order.id(),
                        idempotencyKey
                ));
            }

            existing.updateQuantity(newQuantity);
            taskRepository.save(task);
            return existing;
        });
    }

    public void removeProduct(UUID tenantId, UUID taskId, UUID productId) {
        unitOfWork.runInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            WorkOrder order = workOrderRepository.findById(tenantId, task.workOrderId())
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + task.workOrderId()));

            TaskProduct existing = task.findProduct(productId)
                    .orElseThrow(() -> new TaskProductNotFoundException("Product " + productId + " not found on task " + taskId));

            String idempotencyKey = "wo-task-" + taskId + "-prod-" + productId + "-rem";
            inventoryPort.release(new InventoryPort.StockReservation(
                    productId,
                    order.branch().id(),
                    existing.quantity(),
                    order.id(),
                    idempotencyKey
            ));

            task.removeProduct(productId);
            taskRepository.save(task);
        });
    }

    public TaskProduct proposeProduct(UUID tenantId, UUID taskId, UUID mechanicUserId,
                                      UUID productId, int quantity, BigDecimal unitPrice) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            if (task.assignedMechanic().isEmpty() || !task.assignedMechanic().get().id().equals(mechanicUserId)) {
                throw new IllegalStateException("Only the assigned technician can propose spare parts for this task");
            }

            WorkOrder order = workOrderRepository.findById(tenantId, task.workOrderId())
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + task.workOrderId()));

            if (isClosed(order.status())) {
                throw new WorkOrderClosedException("Cannot allocate parts to closed work order in status: " + order.status());
            }

            TaskProduct product = TaskProduct.createProposed(tId, task.id(), productId, quantity, unitPrice);
            task.addProduct(product);
            taskRepository.save(task);
            return product;
        });
    }

    public TaskProduct updateProposedProductQuantity(UUID tenantId, UUID taskId, UUID mechanicUserId,
                                                     UUID productId, int newQuantity) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrderTask task = taskRepository.findById(tId, TaskId.of(taskId))
                    .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

            if (task.assignedMechanic().isEmpty() || !task.assignedMechanic().get().id().equals(mechanicUserId)) {
                throw new IllegalStateException("Only the assigned technician can modify spare parts for this task");
            }

            TaskProduct existing = task.findProduct(productId)
                    .orElseThrow(() -> new TaskProductNotFoundException("Product " + productId + " not found on task " + taskId));

            if (existing.approvalStatus() != com.erp.workorder.domain.model.TaskProductApprovalStatus.PENDING_SUPERVISOR_CHECK) {
                throw new IllegalStateException("Technician can only adjust quantity while item is PENDING_SUPERVISOR_CHECK");
            }

            existing.updateQuantity(newQuantity);
            taskRepository.save(task);
            return existing;
        });
    }

    public List<TaskProduct> submitSupplementToCustomer(UUID tenantId, UUID workOrderId) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrder order = workOrderRepository.findById(tenantId, workOrderId)
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + workOrderId));
            if (isClosed(order.status())) {
                throw new WorkOrderClosedException("Cannot send supplement for closed work order in status: " + order.status());
            }

            List<WorkOrderTask> tasks = taskRepository.findByWorkOrderId(tId, workOrderId);
            List<TaskProduct> submitted = new java.util.ArrayList<>();
            for (WorkOrderTask task : tasks) {
                boolean modified = false;
                for (TaskProduct product : task.products()) {
                    if (product.approvalStatus() == com.erp.workorder.domain.model.TaskProductApprovalStatus.PENDING_SUPERVISOR_CHECK) {
                        product.sendToCustomer();
                        submitted.add(product);
                        modified = true;
                    }
                }
                if (modified) {
                    taskRepository.save(task);
                }
            }
            return submitted;
        });
    }

    public List<TaskProduct> respondToSupplement(UUID tenantId, UUID workOrderId, boolean approved) {
        return unitOfWork.callInTransaction(() -> {
            TenantId tId = new TenantId(tenantId);
            WorkOrder order = workOrderRepository.findById(tenantId, workOrderId)
                    .orElseThrow(() -> new WorkOrderNotFoundException("Work order not found: " + workOrderId));
            if (isClosed(order.status())) {
                throw new WorkOrderClosedException("Cannot respond to supplement for closed work order in status: " + order.status());
            }

            List<WorkOrderTask> tasks = taskRepository.findByWorkOrderId(tId, workOrderId);
            List<TaskProduct> processed = new java.util.ArrayList<>();
            for (WorkOrderTask task : tasks) {
                boolean modified = false;
                for (TaskProduct product : task.products()) {
                    if (product.approvalStatus() == com.erp.workorder.domain.model.TaskProductApprovalStatus.SENT_TO_CUSTOMER) {
                        if (approved) {
                            String idempotencyKey = "wo-task-" + task.id().value() + "-prod-" + product.productId() + "-supp-app";
                            inventoryPort.reserve(new InventoryPort.StockReservation(
                                    product.productId(),
                                    order.branch().id(),
                                    product.quantity(),
                                    order.id(),
                                    idempotencyKey
                            ));
                            product.approveByCustomer();
                        } else {
                            product.rejectByCustomer();
                        }
                        processed.add(product);
                        modified = true;
                    }
                }
                if (modified) {
                    taskRepository.save(task);
                }
            }
            return processed;
        });
    }

    private boolean isClosed(WorkOrderStatus status) {
        return status == WorkOrderStatus.DELIVERED
                || status == WorkOrderStatus.REJECTED
                || status == WorkOrderStatus.CANCELLED;
    }
}
