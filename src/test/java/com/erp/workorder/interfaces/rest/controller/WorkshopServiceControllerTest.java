package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.TestJwksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WorkshopServiceControllerTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private UUID tenant;
    private UUID branch;
    private String adminToken;
    private String technicianToken;
    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = UUID.randomUUID();
        branch = UUID.randomUUID();
        adminToken = TestJwksServer.signToken(tenant, List.of("ADMIN_TALLER"));
        technicianToken = TestJwksServer.signToken(tenant, List.of("TECHNICIAN"));
        customerToken = TestJwksServer.signToken(tenant, List.of("CLIENTE"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM workshop_services");
        }
    }

    @Test
    void createsListsUpdatesAndDeletesService() throws Exception {
        // 1. Create service (ADMIN_TALLER)
        String createJson = """
                {
                    "branchId": "%s",
                    "name": "Brake Rotor Resurfacing",
                    "price": 85.00
                }
                """.formatted(branch);

        MvcResult createResult = mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Brake Rotor Resurfacing"))
                .andExpect(jsonPath("$.price").value(85.00))
                .andReturn();

        String serviceId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // 2. List services (TECHNICIAN)
        mockMvc.perform(get("/api/v1/services?branchId=" + branch)
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Brake Rotor Resurfacing"));

        // 3. Update service (ADMIN_TALLER)
        String updateJson = """
                {
                    "name": "Brake Rotor Resurfacing & Balance",
                    "price": 95.00
                }
                """;

        mockMvc.perform(put("/api/v1/services/" + serviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Brake Rotor Resurfacing & Balance"))
                .andExpect(jsonPath("$.price").value(95.00));

        // 4. Delete service (ADMIN_TALLER)
        mockMvc.perform(delete("/api/v1/services/" + serviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get("/api/v1/services?branchId=" + branch)
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsCreationByUnauthorizedRole() throws Exception {
        String createJson = """
                {
                    "branchId": "%s",
                    "name": "Unauthorized Service",
                    "price": 10.00
                }
                """.formatted(branch);

        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/services?branchId=" + branch))
                .andExpect(status().isUnauthorized());
    }
}
