package com.erp.workorder.infrastructure.persistence.adapter;

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
import com.erp.workorder.domain.model.WorkOrderTask;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkOrderTaskPersistenceTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private WorkOrderTaskPersistenceAdapter taskAdapter;

    @Autowired
    private WorkshopServicePersistenceAdapter serviceAdapter;

    @Autowired
    private WorkOrderRepositoryPort orderRepository;

    @Autowired
    private UnitOfWork unitOfWork;

    @BeforeEach
    @AfterEach
    void cleanup() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_task_products");
            statement.execute("DELETE FROM work_order_tasks");
            statement.execute("DELETE FROM workshop_services");
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
        }
    }

    @Test
    void persistsTaskWithProductsAndRetrieves() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        try (TenantContextScope ignored = TenantContextScope.forTenant(tenant)) {
            // Seed parent work order
            WorkOrder order = WorkOrder.opened(
                    orderId,
                    new TenantId(tenant),
                    WorkOrderNumber.of(1),
                    new CustomerRef(UUID.randomUUID()),
                    new VehicleRef(UUID.randomUUID()),
                    new BranchId(branch),
                    "Diagnostics",
                    Instant.now(),
                    UUID.randomUUID()
            );
            unitOfWork.runInTransaction(() -> orderRepository.save(order));

            // Seed workshop service
            WorkshopService service = WorkshopService.create(
                    new TenantId(tenant),
                    new BranchId(branch),
                    "Suspension check",
                    new BigDecimal("40.00")
            );
            unitOfWork.runInTransaction(() -> serviceAdapter.save(service));

            // Create task
            WorkOrderTask task = WorkOrderTask.create(
                    new TenantId(tenant),
                    orderId,
                    service.id(),
                    "Check rear shock absorbers",
                    List.of("http://photo1.jpg", "http://photo2.jpg")
            );

            UUID productId = UUID.randomUUID();
            TaskProduct product = TaskProduct.create(new TenantId(tenant), task.id(), productId, 2, new BigDecimal("120.00"));
            task.addProduct(product);

            unitOfWork.runInTransaction(() -> taskAdapter.save(task));

            // Retrieve
            Optional<WorkOrderTask> found = unitOfWork.callInTransaction(() ->
                    taskAdapter.findById(new TenantId(tenant), task.id()));

            assertTrue(found.isPresent());
            assertEquals("Check rear shock absorbers", found.get().description());
            assertEquals(TaskStatus.PENDING, found.get().status());
            assertEquals(2, found.get().evidenceImages().size());
            assertEquals(1, found.get().products().size());
            assertEquals(new BigDecimal("240.00"), found.get().totalAmount());

            // Transition
            found.get().start();
            unitOfWork.runInTransaction(() -> taskAdapter.save(found.get()));

            Optional<WorkOrderTask> started = unitOfWork.callInTransaction(() ->
                    taskAdapter.findById(new TenantId(tenant), task.id()));
            assertTrue(started.isPresent());
            assertEquals(TaskStatus.DOING, started.get().status());
            assertTrue(started.get().startedAt().isPresent());

            // Delete
            unitOfWork.runInTransaction(() -> taskAdapter.delete(new TenantId(tenant), task.id()));
            Optional<WorkOrderTask> deleted = unitOfWork.callInTransaction(() ->
                    taskAdapter.findById(new TenantId(tenant), task.id()));
            assertTrue(deleted.isEmpty());
        }
    }
}
