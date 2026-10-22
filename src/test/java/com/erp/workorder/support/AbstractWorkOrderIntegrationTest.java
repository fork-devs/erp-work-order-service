package com.erp.workorder.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the work order service integration tests.
 *
 * <p>Starts a real PostgreSQL in process and connects the application with
 * the EXECUTION role {@code erp_work_order_app}, which is not the owner of
 * the tables. The Flyway migrations run as the owner.
 */
@SpringBootTest
public abstract class AbstractWorkOrderIntegrationTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.start();

        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::appRole);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::appPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.flyway.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.flyway.user", EmbeddedPostgresSupport::ownerUser);
        registry.add("spring.flyway.password", EmbeddedPostgresSupport::ownerPassword);
        registry.add("spring.flyway.placeholders.appRolePassword", EmbeddedPostgresSupport::appPassword);

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
