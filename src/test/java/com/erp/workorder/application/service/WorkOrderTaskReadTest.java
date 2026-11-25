package com.erp.workorder.application.service;

import com.erp.workorder.domain.model.*;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkOrderTaskReadTest {

    @Test
    void listTasks_AllowsOwnerCustomer_AndDeniesOtherCustomer() {
        WorkOrderRepositoryPort orderRepo = mock(WorkOrderRepositoryPort.class);
        WorkOrderTaskRepositoryPort taskRepo = mock(WorkOrderTaskRepositoryPort.class);
        WorkshopServiceRepositoryPort serviceRepo = mock(WorkshopServiceRepositoryPort.class);
        InventoryPort inventoryPort = mock(InventoryPort.class);
        UnitOfWork unitOfWork = new UnitOfWork() {
            @Override
            public void runInTransaction(Runnable work) { work.run(); }
            @Override
            public <T> T callInTransaction(java.util.function.Supplier<T> work) { return work.get(); }
        };

        ManageWorkOrderTaskUseCase useCase = new ManageWorkOrderTaskUseCase(
                orderRepo, taskRepo, serviceRepo, inventoryPort, unitOfWork);

        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID ownerCustomerId = UUID.randomUUID();
        UUID otherCustomerId = UUID.randomUUID();

        WorkOrder order = mock(WorkOrder.class);
        when(order.customer()).thenReturn(new CustomerRef(ownerCustomerId));
        when(orderRepo.findById(tenantId, orderId)).thenReturn(Optional.of(order));

        WorkOrderTask task = new WorkOrderTask(
                TaskId.generate(), new TenantId(tenantId), orderId, ServiceId.of(UUID.randomUUID()),
                null, "Brake check", TaskStatus.DOING,
                List.of("https://cdn.example.com/photo.jpg"), Instant.now(), null,
                List.of(), Instant.now(), Instant.now()
        );
        when(taskRepo.findByWorkOrderId(new TenantId(tenantId), orderId)).thenReturn(List.of(task));

        List<WorkOrderTask> ownerResult = useCase.listTasks(tenantId, orderId, ownerCustomerId, true);
        assertEquals(1, ownerResult.size());
        assertEquals(TaskStatus.DOING, ownerResult.get(0).status());

        assertThrows(AccessDeniedException.class, () ->
                useCase.listTasks(tenantId, orderId, otherCustomerId, true));
    }
}
