package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.application.service.ExecuteWorkOrderTaskUseCase;
import com.erp.workorder.application.service.ManageWorkOrderTaskUseCase;
import com.erp.workorder.application.service.TaskProductAllocationUseCase;
import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.interfaces.rest.dto.request.AddTaskProductRequest;
import com.erp.workorder.interfaces.rest.dto.request.AssignMechanicRequest;
import com.erp.workorder.interfaces.rest.dto.request.CreateTaskRequest;
import com.erp.workorder.interfaces.rest.dto.request.UpdateTaskProductQuantityRequest;
import com.erp.workorder.interfaces.rest.dto.request.UpdateTaskRequest;
import com.erp.workorder.interfaces.rest.dto.response.TaskProductResponse;
import com.erp.workorder.interfaces.rest.dto.response.TaskResponse;
import com.erp.workorder.interfaces.rest.support.RequestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Work Order Tasks", description = "Operational tasks and allocated spare parts for work orders")
public class WorkOrderTaskController {

    private final ManageWorkOrderTaskUseCase manageTaskUseCase;
    private final ExecuteWorkOrderTaskUseCase executeTaskUseCase;
    private final TaskProductAllocationUseCase productAllocationUseCase;

    public WorkOrderTaskController(ManageWorkOrderTaskUseCase manageTaskUseCase,
                                   ExecuteWorkOrderTaskUseCase executeTaskUseCase,
                                   TaskProductAllocationUseCase productAllocationUseCase) {
        this.manageTaskUseCase = manageTaskUseCase;
        this.executeTaskUseCase = executeTaskUseCase;
        this.productAllocationUseCase = productAllocationUseCase;
    }

    @PostMapping("/api/v1/work-orders/{id}/tasks")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Add a task to a work order")
    public ResponseEntity<TaskResponse> addTask(@PathVariable("id") UUID workOrderId,
                                                @Valid @RequestBody CreateTaskRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTask task = manageTaskUseCase.addTask(
                tenantId,
                workOrderId,
                request.serviceId(),
                request.description(),
                request.evidenceImages()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(task));
    }

    @PutMapping("/api/v1/work-orders/{id}/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Update task details")
    public ResponseEntity<TaskResponse> updateTask(@PathVariable("id") UUID workOrderId,
                                                   @PathVariable("taskId") UUID taskId,
                                                   @Valid @RequestBody UpdateTaskRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTask task = manageTaskUseCase.updateTask(
                tenantId,
                workOrderId,
                taskId,
                request.serviceId(),
                request.assignedMechanicId(),
                request.description(),
                request.evidenceImages()
        );
        return ResponseEntity.ok(toResponse(task));
    }

    @DeleteMapping("/api/v1/work-orders/{id}/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Delete task and release any product reservations")
    public ResponseEntity<Void> deleteTask(@PathVariable("id") UUID workOrderId,
                                           @PathVariable("taskId") UUID taskId) {
        UUID tenantId = RequestPrincipal.tenant();
        manageTaskUseCase.deleteTask(tenantId, workOrderId, taskId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/work-order-tasks/{taskId}/assign-mechanic")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Assign a technician/mechanic to a task")
    public ResponseEntity<TaskResponse> assignMechanic(
            @PathVariable("taskId") UUID taskId,
            @RequestParam(name = "mechanicId", required = false) UUID mechanicIdParam,
            @RequestBody(required = false) AssignMechanicRequest body) {
        UUID tenantId = RequestPrincipal.tenant();
        UUID mechanicId = body != null && body.mechanicId() != null ? body.mechanicId() : mechanicIdParam;
        WorkOrderTask task = executeTaskUseCase.assignTechnician(tenantId, taskId, mechanicId);
        return ResponseEntity.ok(toResponse(task));
    }

    @PostMapping("/api/v1/work-order-tasks/{taskId}/start")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "Start task execution (transitions PENDING to DOING)")
    public ResponseEntity<TaskResponse> startTask(@PathVariable("taskId") UUID taskId) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTask task = executeTaskUseCase.startTask(tenantId, taskId);
        return ResponseEntity.ok(toResponse(task));
    }

    @PostMapping("/api/v1/work-order-tasks/{taskId}/complete")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "Complete task execution (transitions DOING to COMPLETED)")
    public ResponseEntity<TaskResponse> completeTask(@PathVariable("taskId") UUID taskId) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTask task = executeTaskUseCase.completeTask(tenantId, taskId);
        return ResponseEntity.ok(toResponse(task));
    }

    @PostMapping("/api/v1/work-order-tasks/{taskId}/reopen")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Reopen completed task (transitions COMPLETED to DOING)")
    public ResponseEntity<TaskResponse> reopenTask(@PathVariable("taskId") UUID taskId) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTask task = executeTaskUseCase.reopenTask(tenantId, taskId);
        return ResponseEntity.ok(toResponse(task));
    }

    @PostMapping("/api/v1/work-order-tasks/{taskId}/products")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Allocate spare part to task with immediate stock reservation")
    public ResponseEntity<TaskProductResponse> addProduct(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AddTaskProductRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        BigDecimal price = request.unitPrice() != null ? request.unitPrice() : BigDecimal.ZERO;
        TaskProduct product = productAllocationUseCase.addProduct(
                tenantId,
                taskId,
                request.productId(),
                request.quantity(),
                price
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toProductResponse(product));
    }

    @PutMapping("/api/v1/work-order-tasks/{taskId}/products/{productId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Modify allocated spare part quantity with reservation adjustment")
    public ResponseEntity<TaskProductResponse> updateProductQuantity(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("productId") UUID productId,
            @Valid @RequestBody UpdateTaskProductQuantityRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        TaskProduct product = productAllocationUseCase.updateProductQuantity(
                tenantId,
                taskId,
                productId,
                request.quantity()
        );
        return ResponseEntity.ok(toProductResponse(product));
    }

    @DeleteMapping("/api/v1/work-order-tasks/{taskId}/products/{productId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Remove spare part from task and release stock reservation")
    public ResponseEntity<Void> removeProduct(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("productId") UUID productId) {
        UUID tenantId = RequestPrincipal.tenant();
        productAllocationUseCase.removeProduct(tenantId, taskId, productId);
        return ResponseEntity.noContent().build();
    }

    private static TaskResponse toResponse(WorkOrderTask task) {
        List<TaskProductResponse> productResponses = task.products().stream()
                .map(WorkOrderTaskController::toProductResponse)
                .toList();

        return new TaskResponse(
                task.id().value(),
                task.workOrderId(),
                task.serviceId().value(),
                task.assignedMechanic().map(MechanicRef::id).orElse(null),
                task.description(),
                task.status().name(),
                task.evidenceImages(),
                task.startedAt().orElse(null),
                task.completedAt().orElse(null),
                productResponses,
                task.totalAmount(),
                task.createdAt()
        );
    }

    private static TaskProductResponse toProductResponse(TaskProduct product) {
        return new TaskProductResponse(
                product.id().value(),
                product.productId(),
                product.quantity(),
                product.unitPrice(),
                product.totalAmount(),
                product.createdAt()
        );
    }
}
