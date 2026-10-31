package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.application.service.WorkshopServiceUseCase;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.interfaces.rest.dto.request.CreateServiceRequest;
import com.erp.workorder.interfaces.rest.dto.request.UpdateServiceRequest;
import com.erp.workorder.interfaces.rest.dto.response.ServiceResponse;
import com.erp.workorder.interfaces.rest.support.RequestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
@Tag(name = "Workshop services", description = "The workshop service catalog and pricing per branch")
public class WorkshopServiceController {

    private final WorkshopServiceUseCase useCase;

    public WorkshopServiceController(WorkshopServiceUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Create a service in the branch catalog")
    public ResponseEntity<ServiceResponse> create(@Valid @RequestBody CreateServiceRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkshopService service = useCase.create(tenantId, request.branchId(), request.name(), request.price());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Operation(summary = "List active services for a branch")
    public ResponseEntity<List<ServiceResponse>> list(@RequestParam UUID branchId) {
        UUID tenantId = RequestPrincipal.tenant();
        List<ServiceResponse> responses = useCase.listByBranch(tenantId, branchId).stream()
                .map(WorkshopServiceController::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{serviceId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Update service name and price")
    public ResponseEntity<ServiceResponse> update(@PathVariable UUID serviceId,
                                                  @Valid @RequestBody UpdateServiceRequest request) {
        UUID tenantId = RequestPrincipal.tenant();
        WorkshopService updated = useCase.update(tenantId, serviceId, request.name(), request.price());
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{serviceId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Delete service from catalog if not referenced in tasks")
    public ResponseEntity<Void> delete(@PathVariable UUID serviceId) {
        UUID tenantId = RequestPrincipal.tenant();
        useCase.delete(tenantId, serviceId);
        return ResponseEntity.noContent().build();
    }

    private static ServiceResponse toResponse(WorkshopService service) {
        return new ServiceResponse(
                service.id().value(),
                service.branchId().id(),
                service.name(),
                service.price()
        );
    }
}
