package com.erp.workorder.application;

import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transactional boundary holds the promise the design makes: the order
 * row, its history row and its outbox event leave together, or none of them
 * leaves.
 *
 * <p>The business use cases exercise this through the saga (group 6 and 7);
 * here the same three writes run directly against the database to prove the
 * property of the BOUNDARY itself: joined propagation, one commit and an
 * all-or-nothing rollback.
 */
class UnitOfWorkTransactionalityTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private UnitOfWork unitOfWork;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_outbox");
            statement.execute("DELETE FROM work_orders");
        }
    }

    /** The three writes of one transition, inside the caller's active transaction. */
    private void writeTheTransition(UUID tenantId, UUID orderId) {
        jdbcTemplate.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        jdbcTemplate.update(
                "INSERT INTO work_orders (id, tenant_id, number, customer_id, vehicle_id, branch_id, status) "
                        + "VALUES (?, ?, ?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'COTIZADA')",
                orderId, tenantId, "WO-000001");
        jdbcTemplate.update(
                "INSERT INTO work_order_status_history (tenant_id, work_order_id, to_status) "
                        + "VALUES (?, ?, 'COTIZADA')", tenantId, orderId);
        jdbcTemplate.update(
                "INSERT INTO work_order_outbox (tenant_id, aggregate_id, event_type, event_id, payload) "
                        + "VALUES (?, ?, 'WorkOrderCompleted', ?, CAST(? AS jsonb))",
                tenantId, orderId, UUID.randomUUID(), "{\"order\":\"" + orderId + "\"}");
    }

    private int count(String table) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Test
    void theThreeWritesOfOneTransitionCommitTogether() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        unitOfWork.runInTransaction(() -> writeTheTransition(tenantId, orderId));

        assertEquals(1, count("work_orders"));
        assertEquals(1, count("work_order_status_history"));
        assertEquals(1, count("work_order_outbox"));
    }

    @Test
    void aFailureAfterTheThreeWritesConfirmsNone() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () ->
                unitOfWork.runInTransaction(() -> {
                    writeTheTransition(tenantId, orderId);
                    throw new IllegalStateException("the relay or the mapping failed after the writes");
                }));

        assertEquals(0, count("work_orders"), "the order must not survive a failed transition");
        assertEquals(0, count("work_order_status_history"), "the history must not survive");
        assertEquals(0, count("work_order_outbox"), "the event must not survive either");
    }

    @Test
    void aNestedCallJoinsTheOuterTransactionInsteadOfOpeningAnother() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () ->
                unitOfWork.runInTransaction(() -> {
                    writeTheTransition(tenantId, orderId);
                    // An inner demarcation JOINS: a failure inside it must roll
                    // the outer writes back too. With an independent
                    // transaction the order row would survive the crash,
                    // half-committed.
                    unitOfWork.runInTransaction(() -> {
                        throw new IllegalStateException("the inner demarcation failed");
                    });
                }));

        assertEquals(0, count("work_orders"), "the joined transaction rolled the outer write back too");
        assertEquals(0, count("work_order_status_history"));
        assertEquals(0, count("work_order_outbox"));
    }

    @Test
    void readsInsideTheBoundarySeeTheOwnUncommittedWrites() {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Integer ordersInside = unitOfWork.callInTransaction(() -> {
            writeTheTransition(tenantId, orderId);
            Integer seen = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM work_orders",
                    Integer.class);
            assertTrue(seen != null && seen >= 1, "the write of the same boundary is visible inside it");
            return seen;
        });

        assertTrue(ordersInside >= 1);
    }
}
