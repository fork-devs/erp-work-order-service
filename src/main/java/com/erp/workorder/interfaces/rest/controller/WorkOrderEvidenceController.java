package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.infrastructure.adapter.external.MediaUploadClient;
import com.erp.workorder.infrastructure.persistence.entity.WorkOrderEvidenceJpaEntity;
import com.erp.workorder.infrastructure.persistence.repository.SpringDataWorkOrderEvidenceRepository;
import com.erp.workorder.interfaces.rest.support.RequestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-orders/{orderId}/evidences")
@Tag(name = "Work Order Evidences", description = "Immutable multimedia evidence timeline for vehicle intake and repair stages")
public class WorkOrderEvidenceController {

    private static final Set<String> ALLOWED_STAGES = Set.of("RECEPTION", "DIAGNOSIS", "REPAIR", "DELIVERY");

    private final SpringDataWorkOrderEvidenceRepository evidenceRepository;
    private final MediaUploadClient mediaUploadClient;

    public WorkOrderEvidenceController(
            SpringDataWorkOrderEvidenceRepository evidenceRepository,
            MediaUploadClient mediaUploadClient) {
        this.evidenceRepository = evidenceRepository;
        this.mediaUploadClient = mediaUploadClient;
    }

    public record EvidenceResponse(
            UUID id,
            UUID workOrderId,
            String stage,
            String mediaId,
            String imageUrl,
            String caption,
            UUID uploadedBy,
            Instant createdAt,
            boolean immutable
    ) {
        static EvidenceResponse from(WorkOrderEvidenceJpaEntity e) {
            return new EvidenceResponse(
                    e.getId(), e.getWorkOrderId(), e.getStage(), e.getMediaId(),
                    e.getImageUrl(), e.getCaption(), e.getUploadedBy(), e.getCreatedAt(), true
            );
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO')")
    @Transactional
    @Operation(summary = "Upload intake/inspection photo evidence", description = "Uploads a photo to Cloudflare R2 and appends an immutable entry to the work order timeline")
    public ResponseEntity<EvidenceResponse> uploadEvidence(
            @PathVariable("orderId") UUID workOrderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "stage", defaultValue = "RECEPTION") String stage,
            @RequestParam(value = "caption", required = false) String caption,
            Authentication authentication) {
        String normalizedStage = stage != null ? stage.trim().toUpperCase() : "RECEPTION";
        if (!ALLOWED_STAGES.contains(normalizedStage)) {
            throw new IllegalArgumentException("Invalid stage. Allowed: RECEPTION, DIAGNOSIS, REPAIR, DELIVERY");
        }

        UUID tenantId = RequestPrincipal.tenant();
        UUID userId = null;
        if (authentication != null && authentication.getName() != null) {
            try {
                userId = UUID.fromString(authentication.getName());
            } catch (IllegalArgumentException ignored) {
                userId = null;
            }
        }
        MediaUploadClient.UploadedMedia uploaded = mediaUploadClient.upload(
                file, "work-orders/" + workOrderId + "/" + normalizedStage.toLowerCase());

        WorkOrderEvidenceJpaEntity entity = new WorkOrderEvidenceJpaEntity(
                UUID.randomUUID(),
                tenantId,
                workOrderId,
                normalizedStage,
                uploaded.mediaId(),
                uploaded.url(),
                caption,
                userId,
                Instant.now()
        );
        evidenceRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(EvidenceResponse.from(entity));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'WORKSHOP_ADMIN', 'ADMIN_TALLER', 'SERVICE_ADVISOR', 'ASESOR', 'TECHNICIAN', 'MECANICO', 'CUSTOMER', 'CLIENTE')")
    @Transactional(readOnly = true)
    @Operation(summary = "List chronological evidence timeline for work order")
    public ResponseEntity<List<EvidenceResponse>> listEvidences(@PathVariable("orderId") UUID workOrderId) {
        UUID tenantId = RequestPrincipal.tenant();
        List<EvidenceResponse> timeline = evidenceRepository
                .findByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId, workOrderId)
                .stream()
                .map(EvidenceResponse::from)
                .toList();
        return ResponseEntity.ok(timeline);
    }
}
