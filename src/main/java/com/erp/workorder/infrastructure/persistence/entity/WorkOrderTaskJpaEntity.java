package com.erp.workorder.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "work_order_tasks")
public class WorkOrderTaskJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "assigned_mechanic_id")
    private UUID assignedMechanicId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String status;

    @Column(name = "evidence_images")
    private String[] evidenceImages;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<WorkOrderTaskProductJpaEntity> products = new ArrayList<>();

    public WorkOrderTaskJpaEntity() {}

    public WorkOrderTaskJpaEntity(UUID id,
                                  UUID tenantId,
                                  UUID workOrderId,
                                  UUID serviceId,
                                  UUID assignedMechanicId,
                                  String description,
                                  String status,
                                  String[] evidenceImages,
                                  Instant startedAt,
                                  Instant completedAt,
                                  Instant createdAt,
                                  Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workOrderId = workOrderId;
        this.serviceId = serviceId;
        this.assignedMechanicId = assignedMechanicId;
        this.description = description;
        this.status = status;
        this.evidenceImages = evidenceImages;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }
    public UUID getServiceId() { return serviceId; }
    public void setServiceId(UUID serviceId) { this.serviceId = serviceId; }
    public UUID getAssignedMechanicId() { return assignedMechanicId; }
    public void setAssignedMechanicId(UUID assignedMechanicId) { this.assignedMechanicId = assignedMechanicId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String[] getEvidenceImages() { return evidenceImages; }
    public void setEvidenceImages(String[] evidenceImages) { this.evidenceImages = evidenceImages; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<WorkOrderTaskProductJpaEntity> getProducts() { return products; }
    public void setProducts(List<WorkOrderTaskProductJpaEntity> products) { this.products = products; }
}
