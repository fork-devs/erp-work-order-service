package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.application.service.ExecuteWorkOrderTaskUseCase;
import com.erp.workorder.application.service.ManageWorkOrderTaskUseCase;
import com.erp.workorder.application.service.TaskProductAllocationUseCase;
import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.infrastructure.adapter.external.MediaUploadClient;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderTaskJpaEntity;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkOrderTaskRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "Work Order Tasks", description = "Operational tasks and allocated spare parts for work orders")
public class WorkOrderTaskController {

    private final ManageWorkOrderTaskUseCase manageTaskUseCase;
    private final ExecuteWorkOrderTaskUseCase executeTaskUseCase;
    private final TaskProductAllocationUseCase productAllocationUseCase;

    @Autowired(required = false)
    private MediaUploadClient mediaUploadClient;

    @Autowired(required = false)
    private SpringDataWorkOrderTaskRepository taskRepository;

    public WorkOrderTaskController(ManageWorkOrderTaskUseCase manageTaskUseCase,
                                   ExecuteWorkOrderTaskUseCase executeTaskUseCase,
                                   TaskProductAllocationUseCase productAllocationUseCase) {
        this.manageTaskUseCase = manageTaskUseCase;
        this.executeTaskUseCase = executeTaskUseCase;
        this.productAllocationUseCase = productAllocationUseCase;
    }

    @GetMapping("/api/v1/work-orders/{id}/tasks")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO', 'CUSTOMER', 'CLIENTE')")
    @Operation(summary = "List tasks of a work order (read-only)", description = "Returns task status, technician evidence images, and spare parts. Customers can only view their own orders.")
    public ResponseEntity<List<TaskResponse>> listTasks(
            @PathVariable("id") UUID workOrderId,
            Authentication authentication) {
        UUID tenantId = RequestPrincipal.tenant();
        UUID userId = RequestPrincipal.userId();
        boolean customerOnly = isCustomerOnly(authentication);
        List<TaskResponse> responses = manageTaskUseCase.listTasks(tenantId, workOrderId, userId, customerOnly)
                .stream()
                .map(WorkOrderTaskController::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    private static boolean isCustomerOnly(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        boolean hasCustomer = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()) || "ROLE_CLIENTE".equals(a.getAuthority()));
        boolean hasStaff = authentication.getAuthorities().stream()
                .anyMatch(a -> Set.of(
                        "ROLE_SUPERADMIN", "ROLE_WORKSHOP_ADMIN", "ROLE_ADMIN_TALLER",
                        "ROLE_SERVICE_ADVISOR", "ROLE_ASESOR",
                        "ROLE_TECHNICIAN", "ROLE_MECANICO").contains(a.getAuthority()));
        return hasCustomer && !hasStaff;
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

    @PostMapping(value = "/api/v1/work-orders/{id}/tasks/{taskId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "Upload technician task evidence image to Cloudflare R2")
    public ResponseEntity<TaskResponse> uploadTaskEvidence(
            @PathVariable("id") UUID workOrderId,
            @PathVariable("taskId") UUID taskId,
            @RequestParam("file") MultipartFile file) {
        if (mediaUploadClient == null || taskRepository == null) {
            throw new IllegalStateException("Media upload dependencies are not configured");
        }
        UUID tenantId = RequestPrincipal.tenant();
        WorkOrderTaskJpaEntity entity = taskRepository.findById(taskId)
                .filter(t -> tenantId.equals(t.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        MediaUploadClient.UploadedMedia uploaded = mediaUploadClient.upload(
                file, "work-orders/" + workOrderId + "/tasks/" + taskId);
        List<String> updatedImages = new java.util.ArrayList<>(
                entity.getEvidenceImages() != null ? java.util.Arrays.asList(entity.getEvidenceImages()) : List.of());
        updatedImages.add(uploaded.url());
        WorkOrderTask updated = manageTaskUseCase.updateTask(
                tenantId,
                workOrderId,
                taskId,
                entity.getServiceId(),
                entity.getAssignedMechanicId(),
                entity.getDescription(),
                updatedImages
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(updated));
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
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "Allocate or propose spare part to task (technician proposals require supervisor check)")
    public ResponseEntity<TaskProductResponse> addProduct(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AddTaskProductRequest request,
            Authentication authentication) {
        UUID tenantId = RequestPrincipal.tenant();
        BigDecimal price = request.unitPrice() != null ? request.unitPrice() : BigDecimal.ZERO;
        TaskProduct product;
        if (isTechnicianOnly(authentication)) {
            UUID mechanicId = UUID.fromString(authentication.getName());
            product = productAllocationUseCase.proposeProduct(
                    tenantId,
                    taskId,
                    mechanicId,
                    request.productId(),
                    request.quantity(),
                    price
            );
        } else {
            product = productAllocationUseCase.addProduct(
                    tenantId,
                    taskId,
                    request.productId(),
                    request.quantity(),
                    price
            );
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toProductResponse(product));
    }

    @PutMapping("/api/v1/work-order-tasks/{taskId}/products/{productId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "Modify allocated or proposed spare part quantity")
    public ResponseEntity<TaskProductResponse> updateProductQuantity(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("productId") UUID productId,
            @Valid @RequestBody UpdateTaskProductQuantityRequest request,
            Authentication authentication) {
        UUID tenantId = RequestPrincipal.tenant();
        TaskProduct product;
        if (isTechnicianOnly(authentication)) {
            UUID mechanicId = UUID.fromString(authentication.getName());
            product = productAllocationUseCase.updateProposedProductQuantity(
                    tenantId,
                    taskId,
                    mechanicId,
                    productId,
                    request.quantity()
            );
        } else {
            product = productAllocationUseCase.updateProductQuantity(
                    tenantId,
                    taskId,
                    productId,
                    request.quantity()
            );
        }
        return ResponseEntity.ok(toProductResponse(product));
    }

    @PostMapping("/api/v1/work-orders/{orderId}/supplements/send-to-customer")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR')")
    @Operation(summary = "Supervisor check: batch technician spare-part proposals and send supplement quote to customer")
    public ResponseEntity<List<TaskProductResponse>> sendSupplementToCustomer(@PathVariable("orderId") UUID orderId) {
        UUID tenantId = RequestPrincipal.tenant();
        List<TaskProductResponse> sent = productAllocationUseCase.submitSupplementToCustomer(tenantId, orderId)
                .stream()
                .map(WorkOrderTaskController::toProductResponse)
                .toList();
        return ResponseEntity.ok(sent);
    }

    @PostMapping("/api/v1/work-orders/{orderId}/supplements/approve")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'CUSTOMER', 'CLIENTE')")
    @Operation(summary = "Customer approves additional spare-part supplement and reserves stock")
    public ResponseEntity<List<TaskProductResponse>> approveSupplement(@PathVariable("orderId") UUID orderId) {
        UUID tenantId = RequestPrincipal.tenant();
        List<TaskProductResponse> approved = productAllocationUseCase.respondToSupplement(tenantId, orderId, true)
                .stream()
                .map(WorkOrderTaskController::toProductResponse)
                .toList();
        return ResponseEntity.ok(approved);
    }

    @PostMapping("/api/v1/work-orders/{orderId}/supplements/reject")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'CUSTOMER', 'CLIENTE')")
    @Operation(summary = "Customer rejects additional spare-part supplement")
    public ResponseEntity<List<TaskProductResponse>> rejectSupplement(@PathVariable("orderId") UUID orderId) {
        UUID tenantId = RequestPrincipal.tenant();
        List<TaskProductResponse> rejected = productAllocationUseCase.respondToSupplement(tenantId, orderId, false)
                .stream()
                .map(WorkOrderTaskController::toProductResponse)
                .toList();
        return ResponseEntity.ok(rejected);
    }

    private static boolean isTechnicianOnly(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        boolean hasTech = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TECHNICIAN".equals(a.getAuthority()) || "ROLE_MECANICO".equals(a.getAuthority()));
        boolean hasSupervisor = authentication.getAuthorities().stream()
                .anyMatch(a -> Set.of(
                        "ROLE_SUPERADMIN", "ROLE_WORKSHOP_ADMIN", "ROLE_ADMIN_TALLER",
                        "ROLE_SERVICE_ADVISOR", "ROLE_ASESOR").contains(a.getAuthority()));
        return hasTech && !hasSupervisor;
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
                product.approvalStatus().name(),
                product.createdAt()
        );
    }
}
