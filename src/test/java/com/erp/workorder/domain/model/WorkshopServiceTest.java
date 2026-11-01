package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkshopServiceTest {

    @Test
    void createsWorkshopServiceSuccessfully() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        BranchId branchId = new BranchId(UUID.randomUUID());
        WorkshopService service = WorkshopService.create(tenantId, branchId, "Brake Pad Replacement", new BigDecimal("75.00"));

        assertNotNull(service.id());
        assertEquals(tenantId, service.tenantId());
        assertEquals(branchId, service.branchId());
        assertEquals("Brake Pad Replacement", service.name());
        assertEquals(new BigDecimal("75.00"), service.price());
        assertNotNull(service.createdAt());
        assertNotNull(service.updatedAt());
    }

    @Test
    void validatesMandatoryName() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        BranchId branchId = new BranchId(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () ->
                WorkshopService.create(tenantId, branchId, "", new BigDecimal("75.00")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkshopService.create(tenantId, branchId, null, new BigDecimal("75.00")));
    }

    @Test
    void validatesNonNegativePrice() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        BranchId branchId = new BranchId(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () ->
                WorkshopService.create(tenantId, branchId, "Alignment", new BigDecimal("-10.00")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkshopService.create(tenantId, branchId, "Alignment", null));
    }

    @Test
    void updatesNameAndPrice() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        BranchId branchId = new BranchId(UUID.randomUUID());
        WorkshopService service = WorkshopService.create(tenantId, branchId, "Original Name", new BigDecimal("50.00"));

        service.update("Updated Name", new BigDecimal("65.00"));

        assertEquals("Updated Name", service.name());
        assertEquals(new BigDecimal("65.00"), service.price());
    }
}
