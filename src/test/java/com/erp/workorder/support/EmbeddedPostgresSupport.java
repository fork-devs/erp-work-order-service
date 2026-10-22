package com.erp.workorder.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Arranca una instancia real de PostgreSQL en proceso, para los tests que
 * necesitan verificar el comportamiento de la base de datos y no su
 * simulacion: Row-Level Security, restricciones NOT NULL y migraciones.
 *
 * <p>El entorno de ejecucion no dispone de Docker, de modo que Testcontainers
 * no es viable. {@code embedded-postgres} descarga y arranca una distribucion
 * real de PostgreSQL como proceso nativo, con las migraciones Flyway aplicadas
 * por el rol PROPIETARIO y la aplicacion conectando con el rol de EJECUCION.
 */
public final class EmbeddedPostgresSupport {

    /** Rol de ejecucion de la aplicacion: no es propietario de las tablas. */
    public static final String APP_ROLE = "erp_work_order_app";
    public static final String APP_PASSWORD = "erp_work_order_app_test_password";

    public static final String OWNER_PASSWORD = "erp_work_order_owner_test_password";
    public static final String DATABASE = "postgres";

    private static EmbeddedPostgres postgres;
    private static String jdbcUrl;
    private static String ownerUser;

    private EmbeddedPostgresSupport() {
    }

    public static synchronized void start() {
        if (postgres != null) {
            return;
        }
        try {
            postgres = EmbeddedPostgres.builder()
                    .setServerConfig("listen_addresses", "localhost")
                    .setServerConfig("fsync", "off")
                    .setServerConfig("full_page_writes", "off")
                    .setServerConfig("synchronous_commit", "off")
                    .start();

            ownerUser = resolveOwnerUser();
            jdbcUrl = postgres.getJdbcUrl(DATABASE, ownerUser).split("[?]")[0];

            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER ROLE \"" + ownerUser + "\" WITH PASSWORD '" + OWNER_PASSWORD + "'");
            }
            // El rol de EJECUCION lo crea la migracion V1, con la contrasena
            // que llega por el placeholder appRolePassword de Flyway.
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("No se pudo arrancar PostgreSQL embebido para los tests", e);
        }
    }

    public static synchronized void stop() {
        if (postgres == null) {
            return;
        }
        try {
            postgres.close();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo detener PostgreSQL embebido", e);
        } finally {
            postgres = null;
            jdbcUrl = null;
            ownerUser = null;
        }
    }

    public static String jdbcUrl() {
        start();
        return jdbcUrl;
    }

    public static String ownerUser() {
        start();
        return ownerUser;
    }

    public static String ownerPassword() {
        return OWNER_PASSWORD;
    }

    public static String appRole() {
        return APP_ROLE;
    }

    public static String appPassword() {
        return APP_PASSWORD;
    }

    public static Connection openOwnerConnection() throws SQLException {
        start();
        return java.sql.DriverManager.getConnection(jdbcUrl(), ownerUser(), OWNER_PASSWORD);
    }

    public static Connection openAppConnection() throws SQLException {
        start();
        return java.sql.DriverManager.getConnection(jdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    private static String resolveOwnerUser() throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection()) {
            return connection.getMetaData().getUserName();
        }
    }
}
