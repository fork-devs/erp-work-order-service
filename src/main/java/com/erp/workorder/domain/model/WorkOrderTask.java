package com.erp.workorder.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain entity representing an operational task within a work order.
 * Tracks technician assignment, execution status (PENDING, DOING, COMPLETED),
 * timestamps, and allocated spare parts.
 * Zero framework annotations.
 */
public class WorkOrderTask {

    private final TaskId id;
    private final TenantId tenantId;
    private final UUID workOrderId;
    private ServiceId serviceId;
    private MechanicRef assignedMechanic;
    private String description;
    private TaskStatus status;
    private List<String> evidenceImages;
    private Instant startedAt;
    private Instant completedAt;
    private final List<TaskProduct> products;
    private final Instant createdAt;
    private Instant updatedAt;

    public WorkOrderTask(TaskId id,
                         TenantId tenantId,
                         UUID workOrderId,
                         ServiceId serviceId,
                         MechanicRef assignedMechanic,
                         String description,
                         TaskStatus status,
                         List<String> evidenceImages,
                         Instant startedAt,
                         Instant completedAt,
                         List<TaskProduct> products,
                         Instant createdAt,
                         Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "task id is mandatory");
        this.tenantId = Objects.requireNonNull(tenantId, "tenant id is mandatory");
        this.workOrderId = Objects.requireNonNull(workOrderId, "work order id is mandatory");
        this.serviceId = Objects.requireNonNull(serviceId, "service id is mandatory");
        this.assignedMechanic = assignedMechanic;
        setDescription(description);
        this.status = status != null ? status : TaskStatus.PENDING;
        this.evidenceImages = evidenceImages != null ? new ArrayList<>(evidenceImages) : new ArrayList<>();
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.products = products != null ? new ArrayList<>(products) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static WorkOrderTask create(TenantId tenantId,
                                       UUID workOrderId,
                                       ServiceId serviceId,
                                       String description,
                                       List<String> evidenceImages) {
        return new WorkOrderTask(
                TaskId.generate(),
                tenantId,
                workOrderId,
                serviceId,
                null,
                description,
                TaskStatus.PENDING,
                evidenceImages,
                null,
                null,
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );
    }

    public void assignTechnician(UUID technicianId) {
        this.assignedMechanic = technicianId != null ? new MechanicRef(technicianId) : null;
        this.updatedAt = Instant.now();
    }

    public void start() {
        if (!status.canTransitionTo(TaskStatus.DOING)) {
            throw new IllegalStateException("cannot start task from status " + status);
        }
        this.status = TaskStatus.DOING;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    public void complete() {
        if (!status.canTransitionTo(TaskStatus.COMPLETED)) {
            throw new IllegalStateException("cannot complete task from status " + status);
        }
        this.status = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reopen() {
        if (!status.canTransitionTo(TaskStatus.DOING)) {
            throw new IllegalStateException("cannot reopen task from status " + status);
        }
        this.status = TaskStatus.DOING;
        this.completedAt = null;
        this.updatedAt = Instant.now();
    }

    public void updateDetails(ServiceId serviceId,
                              UUID assignedMechanicId,
                              String description,
                              List<String> evidenceImages) {
        if (serviceId != null) {
            this.serviceId = serviceId;
        }
        if (assignedMechanicId != null) {
            this.assignedMechanic = new MechanicRef(assignedMechanicId);
        }
        if (description != null) {
            setDescription(description);
        }
        if (evidenceImages != null) {
            this.evidenceImages = new ArrayList<>(evidenceImages);
        }
        this.updatedAt = Instant.now();
    }

    public void addProduct(TaskProduct product) {
        Objects.requireNonNull(product, "product cannot be null");
        removeProduct(product.productId());
        this.products.add(product);
        this.updatedAt = Instant.now();
    }

    public boolean removeProduct(UUID productId) {
        boolean removed = this.products.removeIf(p -> p.productId().equals(productId));
        if (removed) {
            this.updatedAt = Instant.now();
        }
        return removed;
    }

    public Optional<TaskProduct> findProduct(UUID productId) {
        return this.products.stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst();
    }

    public BigDecimal totalAmount() {
        return this.products.stream()
                .map(TaskProduct::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void setDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("task description is mandatory");
        }
        this.description = description.strip();
    }

    public TaskId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public ServiceId serviceId() {
        return serviceId;
    }

    public Optional<MechanicRef> assignedMechanic() {
        return Optional.ofNullable(assignedMechanic);
    }

    public String description() {
        return description;
    }

    public TaskStatus status() {
        return status;
    }

    public List<String> evidenceImages() {
        return Collections.unmodifiableList(evidenceImages);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public List<TaskProduct> products() {
        return Collections.unmodifiableList(products);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
