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

    private boolean isClosed(WorkOrderStatus status) {
        return status == WorkOrderStatus.ENTREGADA
                || status == WorkOrderStatus.RECHAZADA
                || status == WorkOrderStatus.CANCELADA;
    }
}
