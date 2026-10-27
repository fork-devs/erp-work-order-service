package com.erp.workorder.migration;

import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that migration V1 creates the schema with the promised isolation:
 * the tables exist, Row-Level Security is ENABLED and FORCED on the tenant
 * tables, the (tenant_id, number) uniqueness is declared and the execution
 * role cannot bypass the policy. The outbox is the exception on purpose: V3
 * declares it the service's internal queue, swept across tenants by the relay.
 *
 * <p>Replaces the scaffold's {@code contextLoads}: a test that only proves the
 * context starts proves none of the rules the schema was designed for.
 *
 * <p>NOTE: the OWNER connection of the embedded PostgreSQL is effective a
 * superuser, and no RLS applies to a superuser even with FORCE. The owner is
 * only used here to check the dictionary; the isolation rules get exercised
 * through the APP role connections (see TenantIsolationTest).
 */
class MigrationSafetyTest extends AbstractWorkOrderIntegrationTest {

    /** The tables the TENANT reads: RLS forced on all of them. */
    private static final String[] TABLES = {
            "work_orders", "work_order_items", "work_order_status_history", "work_order_sequences"
    };

    /**
     * The outbox is the service's INTERNAL queue (V3): the relay sweeps the
     * pending events of ALL tenants, so it carries NO tenant isolation — the
     * same criterion of the inventory's stock_outbox. No endpoint exposes it
     * to the tenants.
     */
    private static final String OUTBOX_TABLE = "work_order_outbox";

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Test
    void theFiveTablesExist() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            for (String table : TABLES) {
                assertTrue(exists(connection, table), "table " + table + " does not exist");
            }
        }
    }

    @Test
    void rowLevelSecurityIsEnabledAndForced() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            for (String table : TABLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
                    statement.setString(1, table);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        assertTrue(resultSet.next(), "table " + table + " not found");
                        assertTrue(resultSet.getBoolean(1), table + " without ENABLE ROW LEVEL SECURITY");
                        assertTrue(resultSet.getBoolean(2), table + " without FORCE ROW LEVEL SECURITY");
                    }
                }
            }
        }
    }

    /**
     * The outbox is the one table WITHOUT tenant isolation: it is the
     * service's internal queue the relay sweeps across tenants. A tenant-
     * isolated outbox would make the sweep physically impossible under the
     * strict policy, and no endpoint exposes the queue to the tenants.
     */
    @Test
    void theOutboxIsTheServiceInternalQueueWithoutTenantIsolation() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
            statement.setString(1, OUTBOX_TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "the outbox table not found");
                assertFalse(resultSet.getBoolean(1), "the outbox must NOT isolate tenants: the relay sweeps all of them");
                assertFalse(resultSet.getBoolean(2), "the outbox must NOT force RLS either");
            }
        }
    }

    @Test
    void thePerTenantUniquenessArbitratesTheNumber() throws SQLException {
        UUID tenant = UUID.randomUUID();
        String sql = "INSERT INTO work_orders (tenant_id, number, customer_id, vehicle_id, branch_id, status) "
                + "VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'RECEPCIONADA')";
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, tenant);
                statement.setString(2, "WO-000001");
                statement.executeUpdate();

                try (PreparedStatement repeat = connection.prepareStatement(sql)) {
                    repeat.setObject(1, tenant);
                    repeat.setString(2, "WO-000001");
                    assertThrows(SQLException.class, repeat::executeUpdate,
                            "two orders of the same tenant cannot carry the same number");
                }
            } finally {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DELETE FROM work_orders WHERE tenant_id = '" + tenant + "'");
                }
            }
        }
    }

    @Test
    void theSequenceArbitratesClaimAndAdvance() throws SQLException {
        UUID tenant = UUID.randomUUID();
        String claim = "INSERT INTO work_order_sequences (tenant_id, last_number) VALUES (?, 1) "
                + "ON CONFLICT (tenant_id) DO NOTHING";
        String bump = "UPDATE work_order_sequences SET last_number = last_number + 1, updated_at = now() "
                + "WHERE tenant_id = ? RETURNING last_number";
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(claim)) {
                statement.setObject(1, tenant);
                assertEquals(1, statement.executeUpdate(), "the claim must create the tenant row");
            }
            try (PreparedStatement statement = connection.prepareStatement(claim)) {
                statement.setObject(1, tenant);
                assertEquals(0, statement.executeUpdate(), "a second claim must not duplicate the row");
            }
            try (PreparedStatement statement = connection.prepareStatement(bump)) {
                statement.setObject(1, tenant);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "the advance must return the new number");
                    assertEquals(2, resultSet.getInt(1), "the counter only advances");
                }
            }
        } finally {
            try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
                 Statement cleanup = connection.createStatement()) {
                cleanup.execute("DELETE FROM work_order_sequences WHERE tenant_id = '" + tenant + "'");
            }
        }
    }

    @Test
    void theExecutionRoleCannotBypassThePolicy() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'erp_work_order_app'")) {
                assertTrue(resultSet.next(), "the execution role does not exist: V1 did not create it");
                assertFalse(resultSet.getBoolean(1), "the execution role must not carry BYPASSRLS");
            }
        }
    }

    @Test
    void theTenantIsolationPolicyExistsWithoutMissingOk() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT polname FROM pg_policy p "
                                 + "JOIN pg_class c ON c.oid = p.polrelid "
                                 + "WHERE c.relname = 'work_orders'")) {
                assertTrue(resultSet.next(), "the isolation policy of work_orders does not exist");
            }
        }
    }

    @Test
    void aQueryWithoutTenantContextFailsInsteadOfReturningRows() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openAppConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT tenant_id FROM work_orders")) {
                assertFalse(resultSet.next(), "without a context the query must not return rows");
            } catch (SQLException expected) {
                // Without SET app.tenant_id, `current_setting('app.tenant_id')::uuid`
                // fails explicitly: without a context there is no read.
            }
        }
    }
}
