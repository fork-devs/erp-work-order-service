package com.erp.workorder.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a spare part allocated to a specific task.
 * Holds the quantity reserved in inventory and its sale price snapshot.
 */
public class TaskProduct {

    private final TaskProductId id;
    private final TenantId tenantId;
    private final TaskId taskId;
    private final UUID productId;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private final Instant createdAt;
    private Instant updatedAt;

    public TaskProduct(TaskProductId id,
                       TenantId tenantId,
                       TaskId taskId,
                       UUID productId,
                       int quantity,
                       BigDecimal unitPrice,
                       Instant createdAt,
                       Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "task product id is mandatory");
        this.tenantId = Objects.requireNonNull(tenantId, "tenant id is mandatory");
        this.taskId = Objects.requireNonNull(taskId, "task id is mandatory");
        this.productId = Objects.requireNonNull(productId, "product id is mandatory");
        setQuantity(quantity);
        setUnitPrice(unitPrice);
        recalculateTotal();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static TaskProduct create(TenantId tenantId, TaskId taskId, UUID productId, int quantity, BigDecimal unitPrice) {
        return new TaskProduct(
                TaskProductId.generate(),
                tenantId,
                taskId,
                productId,
                quantity,
                unitPrice,
                Instant.now(),
                Instant.now()
        );
    }

    public void updateQuantity(int newQuantity) {
        setQuantity(newQuantity);
        recalculateTotal();
        this.updatedAt = Instant.now();
    }

    public void updateUnitPrice(BigDecimal newPrice) {
        setUnitPrice(newPrice);
        recalculateTotal();
        this.updatedAt = Instant.now();
    }

    private void setQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("product quantity must be positive");
        }
        this.quantity = quantity;
    }

    private void setUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("unit price must be non-negative");
        }
        this.unitPrice = unitPrice;
    }

    private void recalculateTotal() {
        this.totalAmount = this.unitPrice.multiply(BigDecimal.valueOf(this.quantity));
    }

    public TaskProductId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public TaskId taskId() {
        return taskId;
    }

    public UUID productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
