package com.erp.workorder.infrastructure.persistence.rls;

import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.infrastructure.persistence.adapter.RlsIsolationProbeAdapter;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Row-Level Security policy fails closed in both directions:
 *
 * <ul>
 *   <li>With a context, the rows of ANOTHER tenant are invisible: not
 *   filtered, not countable, simply foreign.</li>
 *   <li>Without a context, the operation is REJECTED — a silent empty result
 *   would hide the missing context instead of surfacing it.</li>
 * </ul>
 *
 * <p>The context is set by the aspect INSIDE the transaction (advice order
 * declared in PersistenceConfig), which is the only place where a
 * {@code SET LOCAL} applies.
 */
class TenantIsolationTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private RlsIsolationProbeAdapter probe;

    @Autowired
    private UnitOfWork unitOfWork;

    @BeforeEach
    void leaveNoForeignRowsBehind() throws SQLException {
        TenantContextHolder.clear();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_outbox");
            statement.execute("DELETE FROM work_orders");
        }
    }

    @AfterEach
    void clearTheContext() {
        TenantContextHolder.clear();
    }

    private void seed(UUID tenantId, String number) throws SQLException {
        String sql = "INSERT INTO work_orders (tenant_id, number, customer_id, vehicle_id, branch_id, status) "
                + "VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'RECEPCIONADA')";
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
    }

    @Test
    void theRowsOfAnotherTenantAreNotSeenNorCounted() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        seed(tenantA, WorkOrderNumber.of(1).value());
        seed(tenantA, WorkOrderNumber.of(2).value());
        seed(tenantB, WorkOrderNumber.of(1).value());

        try (TenantContextScope scope = TenantContextScope.forTenant(tenantA)) {
            long seenByA = unitOfWork.callInTransaction(probe::countWorkOrders);
            assertEquals(2, seenByA, "tenant A sees exactly its own rows");
        }

        try (TenantContextScope scope = TenantContextScope.forTenant(tenantB)) {
            long seenByB = unitOfWork.callInTransaction(probe::countWorkOrders);
            assertEquals(1, seenByB, "tenant B sees exactly its own rows, none of tenant A's");
        }
    }

    @Test
    void theOperationWithoutContextIsRejectedInsteadOfReturningZeroRows() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        seed(tenantA, WorkOrderNumber.of(1).value());

        assertThrows(TenantContextMissingException.class,
                () -> unitOfWork.callInTransaction(probe::countWorkOrders),
                "without a context the operation must fail closed, not return an empty result");
    }

    @Test
    void thePersistenceOperationOutsideATransactionIsRejected() {
        // No transaction, no context: the aspect cannot guarantee isolation either way.
        assertThrows(TenantContextMissingException.class, probe::countWorkOrders);
    }

    @Test
    void theContextDoesNotSurviveTheBoundary() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        seed(tenantA, WorkOrderNumber.of(1).value());

        try (TenantContextScope scope = TenantContextScope.forTenant(tenantA)) {
            unitOfWork.callInTransaction(probe::countWorkOrders);
        }

        assertThrows(TenantContextMissingException.class,
                () -> unitOfWork.callInTransaction(probe::countWorkOrders),
                "after the request the service has no tenant context; tenant B is not A by accident");
    }
}
