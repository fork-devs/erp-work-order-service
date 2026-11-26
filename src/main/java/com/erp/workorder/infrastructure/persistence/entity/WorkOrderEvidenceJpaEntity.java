package com.erp.workorder.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_order_evidences")
public class WorkOrderEvidenceJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(nullable = false, length = 30)
    private String stage;

    @Column(name = "media_id", nullable = false, length = 100)
    private String mediaId;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WorkOrderEvidenceJpaEntity() {}

    public WorkOrderEvidenceJpaEntity(
            UUID id,
            UUID tenantId,
            UUID workOrderId,
            String stage,
            String mediaId,
            String imageUrl,
            String caption,
            UUID uploadedBy,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workOrderId = workOrderId;
        this.stage = stage;
        this.mediaId = mediaId;
        this.imageUrl = imageUrl;
        this.caption = caption;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWorkOrderId() { return workOrderId; }
    public String getStage() { return stage; }
    public String getMediaId() { return mediaId; }
    public String getImageUrl() { return imageUrl; }
    public String getCaption() { return caption; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
