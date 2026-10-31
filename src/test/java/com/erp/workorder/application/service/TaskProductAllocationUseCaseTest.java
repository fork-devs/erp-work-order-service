package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.TaskProductNotFoundException;
import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.VehicleRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkOrderTaskRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskProductAllocationUseCaseTest {

    private final UnitOfWork directUnitOfWork = new UnitOfWork() {
        @Override
        public void runInTransaction(Runnable work) { work.run(); }
        @Override
        public <T> T callInTransaction(Supplier<T> work) { return work.get(); }
    };

    private InMemoryWorkOrderRepository orderRepo;
    private InMemoryTaskRepository taskRepo;
    private FakeInventoryPort inventoryPort;
    private TaskProductAllocationUseCase useCase;

    private UUID tenantId;
    private UUID workOrderId;
    private UUID branchId;
    private WorkOrderTask task;

    @BeforeEach
    void setUp() {
        orderRepo = new InMemoryWorkOrderRepository();
        taskRepo = new InMemoryTaskRepository();
        inventoryPort = new FakeInventoryPort();

        useCase = new TaskProductAllocationUseCase(orderRepo, taskRepo, inventoryPort, directUnitOfWork);

        tenantId = UUID.randomUUID();
        workOrderId = UUID.randomUUID();
        branchId = UUID.randomUUID();

        WorkOrder order = WorkOrder.opened(
                workOrderId,
                new TenantId(tenantId),
                WorkOrderNumber.of(1),
                new CustomerRef(UUID.randomUUID()),
                new VehicleRef(UUID.randomUUID()),
                new BranchId(branchId),
                "Brake diagnosis",
                Instant.now(),
                UUID.randomUUID()
        );
        orderRepo.save(order);

        task = WorkOrderTask.create(
                new TenantId(tenantId),
                workOrderId,
                ServiceId.generate(),
                "Change front pads",
                List.of()
        );
        taskRepo.save(task);
    }

    @Test
    void allocatesProductWithImmediateStockReservation() {
        UUID productId = UUID.randomUUID();
        TaskProduct product = useCase.addProduct(tenantId, task.id().value(), productId, 2, new BigDecimal("80.00"));

        assertNotNull(product.id());
        assertEquals(productId, product.productId());
        assertEquals(2, product.quantity());
        assertEquals(new BigDecimal("160.00"), product.totalAmount());

        assertEquals(1, inventoryPort.reserved.size());
        assertEquals(productId, inventoryPort.reserved.get(0).sparePartId());
        assertEquals(2, inventoryPort.reserved.get(0).quantity());
    }

    @Test
    void updatesProductQuantityWithReservationAdjustment() {
        UUID productId = UUID.randomUUID();
        useCase.addProduct(tenantId, task.id().value(), productId, 2, new BigDecimal("80.00"));

        // Increase quantity from 2 to 5 -> should reserve 3 more
        TaskProduct updated = useCase.updateProductQuantity(tenantId, task.id().value(), productId, 5);
        assertEquals(5, updated.quantity());
        assertEquals(new BigDecimal("400.00"), updated.totalAmount());
        assertEquals(2, inventoryPort.reserved.size());
        assertEquals(3, inventoryPort.reserved.get(1).quantity());

        // Decrease quantity from 5 to 3 -> should release 2
        updated = useCase.updateProductQuantity(tenantId, task.id().value(), productId, 3);
        assertEquals(3, updated.quantity());
        assertEquals(1, inventoryPort.released.size());
        assertEquals(2, inventoryPort.released.get(0).quantity());
    }

    @Test
    void removesProductAndReleasesAllStock() {
        UUID productId = UUID.randomUUID();
        useCase.addProduct(tenantId, task.id().value(), productId, 4, new BigDecimal("50.00"));

        useCase.removeProduct(tenantId, task.id().value(), productId);

        assertTrue(task.findProduct(productId).isEmpty());
        assertEquals(1, inventoryPort.released.size());
        assertEquals(productId, inventoryPort.released.get(0).sparePartId());
        assertEquals(4, inventoryPort.released.get(0).quantity());
    }

    @Test
    void throwsNotFoundForUnknownProduct() {
        UUID unknownProd = UUID.randomUUID();
        assertThrows(TaskProductNotFoundException.class, () ->
                useCase.updateProductQuantity(tenantId, task.id().value(), unknownProd, 5));
    }

    private static class FakeInventoryPort implements InventoryPort {
        final List<StockReservation> reserved = new ArrayList<>();
        final List<StockReservation> released = new ArrayList<>();

        @Override
        public StockConfirmation reserve(StockReservation reservation) {
            reserved.add(reservation);
            return new StockConfirmation(UUID.randomUUID(), "RESERVA", reservation.quantity());
        }

        @Override
        public StockConfirmation commit(StockReservation reservation) {
            return new StockConfirmation(UUID.randomUUID(), "CONSUMO", reservation.quantity());
        }

        @Override
        public StockConfirmation release(StockReservation reservation) {
            released.add(reservation);
            return new StockConfirmation(UUID.randomUUID(), "LIBERACION", reservation.quantity());
        }
    }

    private static class InMemoryWorkOrderRepository implements WorkOrderRepositoryPort {
        private final Map<UUID, WorkOrder> orders = new HashMap<>();
        @Override
        public void save(WorkOrder order) { orders.put(order.id(), order); }
        @Override
        public void updateStatus(WorkOrder order) { orders.put(order.id(), order); }
        @Override
        public Optional<WorkOrder> findById(UUID tenantId, UUID orderId) {
            WorkOrder o = orders.get(orderId);
            return (o != null && o.tenantId().id().equals(tenantId)) ? Optional.of(o) : Optional.empty();
        }
        @Override
        public List<WorkOrder> listByTenant(UUID tenantId, WorkOrderStatus filter) { return List.of(); }
    }

    private static class InMemoryTaskRepository implements WorkOrderTaskRepositoryPort {
        private final Map<UUID, WorkOrderTask> tasks = new HashMap<>();
        @Override
        public WorkOrderTask save(WorkOrderTask task) { tasks.put(task.id().value(), task); return task; }
        @Override
        public Optional<WorkOrderTask> findById(TenantId tenantId, TaskId id) {
            WorkOrderTask t = tasks.get(id.value());
            return (t != null && t.tenantId().equals(tenantId)) ? Optional.of(t) : Optional.empty();
        }
        @Override
        public List<WorkOrderTask> findByWorkOrderId(TenantId tenantId, UUID workOrderId) { return List.of(); }
        @Override
        public void delete(TenantId tenantId, TaskId id) { tasks.remove(id.value()); }
    }
}
