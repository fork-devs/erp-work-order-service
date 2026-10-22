package com.erp.workorder.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Starts a real PostgreSQL instance in process, for the tests that need to
 * verify the database behavior and not its simulation: Row-Level Security,
 * NOT NULL constraints and migrations.
 *
 * <p>The runtime environment has no Docker, so Testcontainers is not viable.
 * {@code embedded-postgres} downloads and starts a real PostgreSQL
 * distribution as a native process, with the Flyway migrations applied by the
 * OWNER role and the application connecting with the EXECUTION role.
 */
public final class EmbeddedPostgresSupport {

    /** The application EXECUTION role: it is not the owner of the tables. */
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
            // The EXECUTION role is created by migration V1, with the password
            // arriving through the Flyway appRolePassword placeholder.
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not start the embedded PostgreSQL for the tests", e);
        }
    }

    public static synchronized void stop() {
        if (postgres == null) {
            return;
        }
        try {
            postgres.close();
        } catch (IOException e) {
            throw new IllegalStateException("Could not stop the embedded PostgreSQL", e);
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
