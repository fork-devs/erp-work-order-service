package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.ServiceId;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.WorkshopService;
import com.erp.workorder.domain.port.UnitOfWork;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkshopServicePersistenceTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private WorkshopServicePersistenceAdapter adapter;

    @Autowired
    private UnitOfWork unitOfWork;

    @BeforeEach
    @AfterEach
    void cleanup() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM workshop_services");
        }
    }

    @Test
    void persistsAndRetrievesServiceWithinTenantContext() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();

        try (TenantContextScope ignored = TenantContextScope.forTenant(tenant)) {
            WorkshopService service = WorkshopService.create(
                    new TenantId(tenant),
                    new BranchId(branch),
                    "Synthetic Oil Change",
                    new BigDecimal("60.00")
            );

            unitOfWork.runInTransaction(() -> adapter.save(service));

            Optional<WorkshopService> found = unitOfWork.callInTransaction(() ->
                    adapter.findById(new TenantId(tenant), service.id()));

            assertTrue(found.isPresent());
            assertEquals("Synthetic Oil Change", found.get().name());
            assertEquals(new BigDecimal("60.00"), found.get().price());

            List<WorkshopService> byBranch = unitOfWork.callInTransaction(() ->
                    adapter.findByBranch(new TenantId(tenant), new BranchId(branch)));
            assertEquals(1, byBranch.size());

            unitOfWork.runInTransaction(() -> adapter.delete(new TenantId(tenant), service.id()));

            Optional<WorkshopService> deleted = unitOfWork.callInTransaction(() ->
                    adapter.findById(new TenantId(tenant), service.id()));
            assertTrue(deleted.isEmpty());
        }
    }
}
