package com.erp.workorder.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing a workshop labor service in the catalog for a branch.
 * Zero framework annotations.
 */
public class WorkshopService {

    private final ServiceId id;
    private final TenantId tenantId;
    private final BranchId branchId;
    private String name;
    private BigDecimal price;
    private final Instant createdAt;
    private Instant updatedAt;

    public WorkshopService(ServiceId id,
                           TenantId tenantId,
                           BranchId branchId,
                           String name,
                           BigDecimal price,
                           Instant createdAt,
                           Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "service id is mandatory");
        this.tenantId = Objects.requireNonNull(tenantId, "tenant id is mandatory");
        this.branchId = Objects.requireNonNull(branchId, "branch id is mandatory");
        setName(name);
        setPrice(price);
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static WorkshopService create(TenantId tenantId, BranchId branchId, String name, BigDecimal price) {
        return new WorkshopService(
                ServiceId.generate(),
                tenantId,
                branchId,
                name,
                price,
                Instant.now(),
                Instant.now()
        );
    }

    public void update(String name, BigDecimal price) {
        if (name != null) {
            setName(name);
        }
        if (price != null) {
            setPrice(price);
        }
        this.updatedAt = Instant.now();
    }

    private void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("service name is mandatory");
        }
        this.name = name.strip();
    }

    private void setPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("service price must be non-negative");
        }
        this.price = price;
    }

    public ServiceId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public BranchId branchId() {
        return branchId;
    }

    public String name() {
        return name;
    }

    public BigDecimal price() {
        return price;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
