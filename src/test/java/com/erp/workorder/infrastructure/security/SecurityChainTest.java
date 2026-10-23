package com.erp.workorder.infrastructure.security;

import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.TestJwksServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real chain answers: an anonymous request is 401 (never a 403, which
 * would say "no permission" about a caller who never identified himself),
 * a principal without the endpoint's role is 403, and the denial is a
 * recorded security event.
 */
@AutoConfigureMockMvc
class SecurityChainTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String bearer(List<String> roles) {
        return TestJwksServer.signToken(UUID.randomUUID(), roles);
    }

    @Test
    void anAnonymousRequestIsUnauthorizedAndCarriesTheStableSecurityProblem() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/admin/outbox"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aPrincipalWithoutTheEndpointRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/admin/outbox")
                        .header("Authorization", "Bearer " + bearer(List.of("MECANICO"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void aPrincipalWithTheRoleGoesThrough() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/admin/outbox")
                        .header("Authorization", "Bearer " + bearer(List.of("SUPERADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void aWriteAttemptByAnAnonymousCallerIsRefusedTheSameWay() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/admin/outbox/" + UUID.randomUUID() + "/reprocess"))
                .andExpect(status().isUnauthorized());
    }
}
