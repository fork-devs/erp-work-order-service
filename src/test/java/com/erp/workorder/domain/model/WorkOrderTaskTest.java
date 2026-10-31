package com.erp.workorder.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkOrderTaskTest {

    @Test
    void createsTaskInPendingStatus() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID workOrderId = UUID.randomUUID();
        ServiceId serviceId = ServiceId.generate();

        WorkOrderTask task = WorkOrderTask.create(tenantId, workOrderId, serviceId, "Inspect brakes", List.of("http://img1.jpg"));

        assertNotNull(task.id());
        assertEquals(tenantId, task.tenantId());
        assertEquals(workOrderId, task.workOrderId());
        assertEquals(serviceId, task.serviceId());
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals("Inspect brakes", task.description());
        assertEquals(1, task.evidenceImages().size());
        assertTrue(task.assignedMechanic().isEmpty());
        assertTrue(task.startedAt().isEmpty());
        assertTrue(task.completedAt().isEmpty());
        assertTrue(task.products().isEmpty());
    }

    @Test
    void managesExecutionStateTransitions() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID workOrderId = UUID.randomUUID();
        ServiceId serviceId = ServiceId.generate();

        WorkOrderTask task = WorkOrderTask.create(tenantId, workOrderId, serviceId, "Oil change", List.of());

        // Assign technician
        UUID techId = UUID.randomUUID();
        task.assignTechnician(techId);
        assertTrue(task.assignedMechanic().isPresent());
        assertEquals(techId, task.assignedMechanic().get().id());

        // Start task: PENDING -> DOING
        task.start();
        assertEquals(TaskStatus.DOING, task.status());
        assertTrue(task.startedAt().isPresent());
        assertTrue(task.completedAt().isEmpty());

        // Cannot start again
        assertThrows(IllegalStateException.class, task::start);

        // Complete task: DOING -> COMPLETED
        task.complete();
        assertEquals(TaskStatus.COMPLETED, task.status());
        assertTrue(task.completedAt().isPresent());

        // Cannot complete again
        assertThrows(IllegalStateException.class, task::complete);

        // Reopen task: COMPLETED -> DOING
        task.reopen();
        assertEquals(TaskStatus.DOING, task.status());
        assertTrue(task.completedAt().isEmpty());
    }

    @Test
    void managesTaskProductsAndCalculatesTotal() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID workOrderId = UUID.randomUUID();
        ServiceId serviceId = ServiceId.generate();

        WorkOrderTask task = WorkOrderTask.create(tenantId, workOrderId, serviceId, "Tune up", List.of());
        assertEquals(BigDecimal.ZERO, task.totalAmount());

        UUID prod1 = UUID.randomUUID();
        TaskProduct p1 = TaskProduct.create(tenantId, task.id(), prod1, 2, new BigDecimal("45.00"));
        task.addProduct(p1);

        assertEquals(1, task.products().size());
        assertEquals(new BigDecimal("90.00"), task.totalAmount());

        UUID prod2 = UUID.randomUUID();
        TaskProduct p2 = TaskProduct.create(tenantId, task.id(), prod2, 1, new BigDecimal("30.00"));
        task.addProduct(p2);

        assertEquals(2, task.products().size());
        assertEquals(new BigDecimal("120.00"), task.totalAmount());

        // Update quantity
        p1.updateQuantity(3);
        assertEquals(new BigDecimal("165.00"), task.totalAmount());

        // Remove product
        boolean removed = task.removeProduct(prod2);
        assertTrue(removed);
        assertEquals(1, task.products().size());
        assertEquals(new BigDecimal("135.00"), task.totalAmount());
    }
}
