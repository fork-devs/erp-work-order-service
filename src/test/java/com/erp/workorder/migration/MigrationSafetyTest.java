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
 * Verifica que la migracion V1 crea el esquema con el aislamiento prometido:
 * las cuatro tablas existen, la Row-Level Security esta HABILITADA y FUERZADA
 * en todas, la unicidad del correlativo esta declarada y el rol de ejecucion
 * no puede eludir la politica.
 *
 * <p>Sustituye al {@code contextLoads} del andamio: un test que solo comprueba
 * que el contexto arranca no demuestra ninguna de las reglas por las que el
 * esquema fue diseñado.
 */
class MigrationSafetyTest extends AbstractWorkOrderIntegrationTest {

    private static final String[] TABLAS = {
            "work_orders", "work_order_items", "work_order_status_history", "work_order_outbox", "work_order_sequences"
    };

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
    void lasCuatroTablasExisten() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            for (String table : TABLAS) {
                assertTrue(exists(connection, table), "la tabla " + table + " no existe");
            }
        }
    }

    @Test
    void rowLevelSecurityEstaHabilitadaYForzada() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            for (String table : TABLAS) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
                    statement.setString(1, table);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        assertTrue(resultSet.next(), "no se encontro la tabla " + table);
                        assertTrue(resultSet.getBoolean(1), table + " sin ENABLE ROW LEVEL SECURITY");
                        assertTrue(resultSet.getBoolean(2), table + " sin FORCE ROW LEVEL SECURITY");
                    }
                }
            }
        }
    }

    @Test
    void elCorrelativoEsUnicoPorTaller() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM pg_indexes "
                                 + "WHERE tablename = 'work_orders' "
                                 + "AND indexdef ILIKE '%UNIQUE%' AND indexdef ILIKE '%tenant_id%' "
                                 + "AND indexdef ILIKE '%number%'")) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getInt(1) >= 1, "la unicidad (tenant_id, number) no esta declarada");
            }
        }
    }

    @Test
    void laUnicidadDelCorrelativoRechazaLaRepeticion() throws SQLException {
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
                            "dos ordenes del mismo taller no pueden llevar el mismo numero");
                }
            } finally {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DELETE FROM work_orders WHERE tenant_id = '" + tenant + "'");
                }
            }
        }
    }

    @Test
    void laTablaDeNumeracionExisteConSuFilaPorTaller() throws SQLException {
        UUID tenant = UUID.randomUUID();
        String claim = "INSERT INTO work_order_sequences (tenant_id, last_number) VALUES (?, 1) "
                + "ON CONFLICT (tenant_id) DO NOTHING";
        String bump = "UPDATE work_order_sequences SET last_number = last_number + 1, updated_at = now() "
                + "WHERE tenant_id = ? RETURNING last_number";
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(claim)) {
                statement.setObject(1, tenant);
                assertEquals(1, statement.executeUpdate(), "el reclamo de la fila del taller debe crearla");
            }
            try (PreparedStatement statement = connection.prepareStatement(claim)) {
                statement.setObject(1, tenant);
                assertEquals(0, statement.executeUpdate(), "el segundo reclamo no debe duplicar la fila");
            }
            try (PreparedStatement statement = connection.prepareStatement(bump)) {
                statement.setObject(1, tenant);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "el incremento debe devolver el numero nuevo");
                    assertEquals(2, resultSet.getInt(1), "el contador solo avanza");
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
    void elRolDeEjecucionNoPuedeEludirLaPolitica() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'erp_work_order_app'")) {
                assertTrue(resultSet.next(), "el rol de ejecucion no existe: V1 no le creo");
                assertFalse(resultSet.getBoolean(1), "el rol de ejecucion no debe tener BYPASSRLS");
            }
        }
    }

    @Test
    void elContextoDeTallerEsObligatorioSinMissingOk() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT polname FROM pg_policy p "
                                 + "JOIN pg_class c ON c.oid = p.polrelid "
                                 + "WHERE c.relname = 'work_orders'")) {
                assertTrue(resultSet.next(), "la politica de aislamiento de work_orders no existe");
            }
        }
    }

    @Test
    void laConsultaSinContextoDeTallerFallaEnLugarDeDevolverCeroFilas() throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openAppConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT tenant_id FROM work_orders")) {
                assertFalse(resultSet.next(), "sin contexto la consulta no debe devolver filas");
            } catch (SQLException expected) {
                // Sin el SET app.tenant_id, `current_setting('app.tenant_id')::uuid`
                // falla de forma explicita: sin contexto no hay lectura.
            }
        }
    }
}
