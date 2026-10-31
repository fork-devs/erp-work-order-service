package com.erp.workorder.application.service;

import com.erp.workorder.application.exception.ServiceNotFoundException;
import com.erp.workorder.application.exception.TaskNotFoundException;
import com.erp.workorder.application.exception.WorkOrderClosedException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TaskId;
import com.erp.workorder.domain.model.TaskProduct;
import com.erp.workorder.domain.model.TaskStatus;
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
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import com.erp.workorder.domain.model.WorkshopService;
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

class ManageWorkOrderTaskUseCaseTest {

    private final UnitOfWork directUnitOfWork = new UnitOfWork() {
        @Override
        public void runInTransaction(Runnable work) { work.run(); }
        @Override
        public <T> T callInTransaction(Supplier<T> work) { return work.get(); }
    };

    private InMemoryWorkOrderRepository orderRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryServiceRepository serviceRepo;
    private FakeInventoryPort inventoryPort;
    private ManageWorkOrderTaskUseCase useCase;

    private UUID tenantId;
    private UUID workOrderId;
    private UUID branchId;
    private UUID serviceId;

    @BeforeEach
    void setUp() {
        orderRepo = new InMemoryWorkOrderRepository();
        taskRepo = new InMemoryTaskRepository();
        serviceRepo = new InMemoryServiceRepository();
        inventoryPort = new FakeInventoryPort();

        useCase = new ManageWorkOrderTaskUseCase(orderRepo, taskRepo, serviceRepo, inventoryPort, directUnitOfWork);

        tenantId = UUID.randomUUID();
        workOrderId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        serviceId = UUID.randomUUID();

        // Seed work order
        WorkOrder order = WorkOrder.opened(
                workOrderId,
                new TenantId(tenantId),
                WorkOrderNumber.of(1),
                new CustomerRef(UUID.randomUUID()),
                new VehicleRef(UUID.randomUUID()),
                new BranchId(branchId),
                "Initial inspection",
                Instant.now(),
                UUID.randomUUID()
        );
        orderRepo.save(order);

        // Seed workshop service
        WorkshopService service = new WorkshopService(
                ServiceId.of(serviceId),
                new TenantId(tenantId),
                new BranchId(branchId),
                "Brake Inspection",
                new BigDecimal("50.00"),
                Instant.now(),
                Instant.now()
        );
        serviceRepo.save(service);
    }

    @Test
    void addsTaskToOpenWorkOrder() {
        WorkOrderTask task = useCase.addTask(tenantId, workOrderId, serviceId, "Check brake pads", List.of("http://photo.jpg"));

        assertNotNull(task.id());
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals("Check brake pads", task.description());
        assertEquals(1, task.evidenceImages().size());
    }

    @Test
    void rejectsAddTaskToClosedWorkOrder() {
        WorkOrder order = orderRepo.findById(tenantId, workOrderId).get();
        order.transitionTo(WorkOrderStatus.CANCELADA, UUID.randomUUID(), Instant.now());
        orderRepo.updateStatus(order);

        assertThrows(WorkOrderClosedException.class, () ->
                useCase.addTask(tenantId, workOrderId, serviceId, "Check brake pads", List.of()));
    }

    @Test
    void rejectsAddTaskWhenServiceDoesNotExist() {
        UUID unknownServiceId = UUID.randomUUID();
        assertThrows(ServiceNotFoundException.class, () ->
                useCase.addTask(tenantId, workOrderId, unknownServiceId, "Check brake pads", List.of()));
    }

    @Test
    void updatesTaskDetails() {
        WorkOrderTask task = useCase.addTask(tenantId, workOrderId, serviceId, "Initial desc", List.of());
        UUID techId = UUID.randomUUID();

        WorkOrderTask updated = useCase.updateTask(tenantId, workOrderId, task.id().value(), null, techId, "Updated desc", List.of("http://img2.jpg"));

        assertEquals("Updated desc", updated.description());
        assertEquals(techId, updated.assignedMechanic().get().id());
        assertEquals(1, updated.evidenceImages().size());
    }

    @Test
    void deletesTaskAndReleasesStockReservations() {
        WorkOrderTask task = useCase.addTask(tenantId, workOrderId, serviceId, "Desc", List.of());
        UUID productId = UUID.randomUUID();
        TaskProduct product = TaskProduct.create(new TenantId(tenantId), task.id(), productId, 2, new BigDecimal("100.00"));
        task.addProduct(product);
        taskRepo.save(task);

        useCase.deleteTask(tenantId, workOrderId, task.id().value());

        assertTrue(taskRepo.findById(new TenantId(tenantId), task.id()).isEmpty());
        assertEquals(1, inventoryPort.released.size());
        assertEquals(productId, inventoryPort.released.get(0).sparePartId());
        assertEquals(2, inventoryPort.released.get(0).quantity());
    }

    private static class FakeInventoryPort implements InventoryPort {
        final List<StockReservation> released = new ArrayList<>();
        @Override
        public StockConfirmation reserve(StockReservation reservation) { return new StockConfirmation(UUID.randomUUID(), "RESERVA", reservation.quantity()); }
        @Override
        public StockConfirmation commit(StockReservation reservation) { return new StockConfirmation(UUID.randomUUID(), "CONSUMO", reservation.quantity()); }
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

    private static class InMemoryServiceRepository implements WorkshopServiceRepositoryPort {
        private final Map<UUID, WorkshopService> services = new HashMap<>();
        @Override
        public WorkshopService save(WorkshopService service) { services.put(service.id().value(), service); return service; }
        @Override
        public Optional<WorkshopService> findById(TenantId tenantId, ServiceId id) {
            WorkshopService s = services.get(id.value());
            return (s != null && s.tenantId().equals(tenantId)) ? Optional.of(s) : Optional.empty();
        }
        @Override
        public List<WorkshopService> findByBranch(TenantId tenantId, BranchId branchId) { return List.of(); }
        @Override
        public void delete(TenantId tenantId, ServiceId id) { services.remove(id.value()); }
        @Override
        public boolean hasActiveTaskReferences(TenantId tenantId, ServiceId id) { return false; }
    }
}
