package com.erp.workorder.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The read view of one part of the order ({@code work_order_items}). The
 * part is a LOGICAL reference to the inventory: no foreign key crosses the
 * service boundary.
 */
@Entity
@Table(name = "work_order_items")
public class WorkOrderItemJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrderJpaEntity order;

    @Column(name = "spare_part_id", nullable = false)
    private UUID sparePartId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "labor_price", nullable = false)
    private BigDecimal laborPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WorkOrderItemJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public WorkOrderJpaEntity getOrder() {
        return order;
    }

    public void setOrder(WorkOrderJpaEntity order) {
        this.order = order;
    }

    public UUID getSparePartId() {
        return sparePartId;
    }

    public void setSparePartId(UUID sparePartId) {
        this.sparePartId = sparePartId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getLaborPrice() {
        return laborPrice;
    }

    public void setLaborPrice(BigDecimal laborPrice) {
        this.laborPrice = laborPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
