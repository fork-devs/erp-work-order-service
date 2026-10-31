package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.VehicleRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkshopServiceRepositoryPort;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import com.erp.workorder.support.FakeInventoryPort;
import com.erp.workorder.support.TestJwksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WorkOrderTaskControllerTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkOrderRepositoryPort orderRepo;

    @Autowired
    private WorkshopServiceRepositoryPort serviceRepo;

    @Autowired
    private FakeInventoryPort inventory;

    @Autowired
    private UnitOfWork unitOfWork;

    private UUID tenant;
    private UUID branch;
    private UUID orderId;
    private UUID serviceId;
    private String adminToken;
    private String technicianToken;

    @BeforeEach
    void setUp() throws Exception {
        inventory.reset();
        tenant = UUID.randomUUID();
        branch = UUID.randomUUID();
        orderId = UUID.randomUUID();
        serviceId = UUID.randomUUID();

        adminToken = TestJwksServer.signToken(tenant, List.of("ADMIN_TALLER"));
        technicianToken = TestJwksServer.signToken(tenant, List.of("TECHNICIAN"));

        try (TenantContextScope ignored = TenantContextScope.forTenant(tenant)) {
            // Seed order
            WorkOrder order = WorkOrder.opened(
                    orderId,
                    new TenantId(tenant),
                    WorkOrderNumber.of(1),
                    new CustomerRef(UUID.randomUUID()),
                    new VehicleRef(UUID.randomUUID()),
                    new BranchId(branch),
                    "Vibration issues",
                    Instant.now(),
                    UUID.randomUUID()
            );
            unitOfWork.runInTransaction(() -> orderRepo.save(order));

            // Seed workshop service
            WorkshopService service = new WorkshopService(
                    ServiceId.of(serviceId),
                    new TenantId(tenant),
                    new BranchId(branch),
                    "Brake Caliper Service",
                    new BigDecimal("120.00"),
                    Instant.now(),
                    Instant.now()
            );
            unitOfWork.runInTransaction(() -> serviceRepo.save(service));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        inventory.reset();
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
    void managesFullTaskWorkflowAndSpareParts() throws Exception {
        // 1. Add task to work order
        String createTaskJson = """
                {
                    "serviceId": "%s",
                    "description": "Replace brake pads and bleed lines",
                    "evidenceImages": ["http://cloudinary.com/img1.jpg"]
                }
                """.formatted(serviceId);

        MvcResult createResult = mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTaskJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.description").value("Replace brake pads and bleed lines"))
                .andReturn();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // 2. Assign technician
        UUID techId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/work-order-tasks/" + taskId + "/assign-mechanic")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mechanicId\": \"" + techId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedMechanicId").value(techId.toString()));

        // 3. Start task by TECHNICIAN
        mockMvc.perform(post("/api/v1/work-order-tasks/" + taskId + "/start")
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOING"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());

        // 4. Allocate product with stock reservation
        UUID productId = UUID.randomUUID();
        String addProductJson = """
                {
                    "productId": "%s",
                    "quantity": 2,
                    "unitPrice": 55.00
                }
                """.formatted(productId);

        mockMvc.perform(post("/api/v1/work-order-tasks/" + taskId + "/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addProductJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(110.00));

        // 5. Update product quantity (increase to 3)
        mockMvc.perform(put("/api/v1/work-order-tasks/" + taskId + "/products/" + productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.totalAmount").value(165.00));

        // 6. Complete task by TECHNICIAN
        mockMvc.perform(post("/api/v1/work-order-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        // 7. Reopen task
        mockMvc.perform(post("/api/v1/work-order-tasks/" + taskId + "/reopen")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOING"))
                .andExpect(jsonPath("$.completedAt").isEmpty());

        // 8. Delete task (releases stock)
        mockMvc.perform(delete("/api/v1/work-orders/" + orderId + "/tasks/" + taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
