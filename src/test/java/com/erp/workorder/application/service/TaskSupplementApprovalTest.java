package com.erp.workorder.application.service;

import com.erp.workorder.domain.model.*;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskSupplementApprovalTest {

    @Test
    void technicianProposal_RequiresSupervisorCheckAndCustomerApprovalBeforeReservingStock() {
        WorkOrderTaskRepositoryPort taskRepo = mock(WorkOrderTaskRepositoryPort.class);
        WorkOrderRepositoryPort orderRepo = mock(WorkOrderRepositoryPort.class);
        InventoryPort inventoryPort = mock(InventoryPort.class);
        UnitOfWork unitOfWork = new UnitOfWork() {
            @Override
            public void runInTransaction(Runnable work) { work.run(); }
            @Override
            public <T> T callInTransaction(java.util.function.Supplier<T> work) { return work.get(); }
        };

        TaskProductAllocationUseCase useCase = new TaskProductAllocationUseCase(orderRepo, taskRepo, inventoryPort, unitOfWork);

        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID assignedMechanicId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        WorkOrderTask task = new WorkOrderTask(
                TaskId.of(taskId), new TenantId(tenantId), orderId, ServiceId.of(UUID.randomUUID()),
                new MechanicRef(assignedMechanicId), "Replace brake pads", TaskStatus.DOING,
                List.of(), Instant.now(), null, List.of(), Instant.now(), Instant.now()
        );

        WorkOrder order = mock(WorkOrder.class);
        when(order.id()).thenReturn(orderId);
        when(order.status()).thenReturn(WorkOrderStatus.IN_PROGRESS);
        when(order.branch()).thenReturn(new BranchId(UUID.randomUUID()));

        when(taskRepo.findById(new TenantId(tenantId), TaskId.of(taskId))).thenReturn(Optional.of(task));
        when(taskRepo.findByWorkOrderId(new TenantId(tenantId), orderId)).thenReturn(List.of(task));
        when(orderRepo.findById(tenantId, orderId)).thenReturn(Optional.of(order));

        // 1. Unassigned mechanic is rejected
        assertThrows(IllegalStateException.class, () ->
                useCase.proposeProduct(tenantId, taskId, UUID.randomUUID(), productId, 2, new BigDecimal("50.00")));

        // 2. Assigned mechanic proposes product -> PENDING_SUPERVISOR_CHECK, no stock reserved yet
        TaskProduct proposed = useCase.proposeProduct(tenantId, taskId, assignedMechanicId, productId, 2, new BigDecimal("50.00"));
        assertEquals(TaskProductApprovalStatus.PENDING_SUPERVISOR_CHECK, proposed.approvalStatus());
        verify(inventoryPort, never()).reserve(any());

        // 3. Supervisor batches and sends to customer -> SENT_TO_CUSTOMER
        List<TaskProduct> sent = useCase.submitSupplementToCustomer(tenantId, orderId);
        assertEquals(1, sent.size());
        assertEquals(TaskProductApprovalStatus.SENT_TO_CUSTOMER, sent.get(0).approvalStatus());
        verify(inventoryPort, never()).reserve(any());

        // 4. Customer approves -> reserves stock and transitions to CUSTOMER_APPROVED
        List<TaskProduct> approved = useCase.respondToSupplement(tenantId, orderId, true);
        assertEquals(1, approved.size());
        assertEquals(TaskProductApprovalStatus.CUSTOMER_APPROVED, approved.get(0).approvalStatus());
        verify(inventoryPort, times(1)).reserve(any());
    }
}
