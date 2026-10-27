package com.erp.workorder.infrastructure.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A REAL check of the database.
 *
 * <p>Saying a connection is configured proves nothing: one is OPENED, a
 * query is RUN, and it is CLOSED. A broken pool is thereby distinguished
 * from a healthy service — exactly what an orchestrator needs to decide
 * whether to send traffic.
 */
@Component("workOrderDatabase")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result != null && result == 1) {
                return Health.up().withDetail("probe", "query executed").build();
            }
            return Health.down().withDetail("probe", "the query did not return what was expected").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
