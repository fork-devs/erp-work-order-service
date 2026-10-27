package com.erp.workorder.infrastructure.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The service's readiness.
 *
 * <p>It REALLY probes its one hard dependency: the database. It does not
 * merely claim the configuration exists — the query runs, so a broken pool
 * is distinguished from a healthy service.
 *
 * <p>The service reports NOT READY when the probe fails: that is what an
 * orchestrator needs to stop sending traffic to it.
 */
@Component("workOrderReadiness")
public class ReadinessIndicator implements HealthIndicator {

    private final DatabaseHealthIndicator database;

    public ReadinessIndicator(DatabaseHealthIndicator database) {
        this.database = database;
    }

    @Override
    public Health health() {
        Health databaseHealth = database.health();

        return (databaseHealth.getStatus() == Status.DOWN ? Health.down() : Health.up())
                .withDetail("database", databaseHealth.getStatus().getCode())
                .build();
    }
}
