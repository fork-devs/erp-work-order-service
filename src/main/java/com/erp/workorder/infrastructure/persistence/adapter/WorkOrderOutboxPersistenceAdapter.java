package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.port.WorkOrderOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The write side of the Transactional Outbox. The INSERT joins the
 * transaction the application layer opened through {@code UnitOfWork} —
 * the adapter declares NO demarcation of its own, so the event row and the
 * order's status change commit together or not at all.
 *
 * <p>The {@code event_id} is the consumer's deduplication key, so a
 * repetition of the SAME event never reinserts a second row
 * ({@code ON CONFLICT DO NOTHING}).
 */
@Component
public class WorkOrderOutboxPersistenceAdapter implements WorkOrderOutboxPort {

    private static final String INSERT_EVENT = """
            INSERT INTO work_order_outbox (tenant_id, aggregate_id, event_type, event_id, payload)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public WorkOrderOutboxPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(WorkOrderOutboxEvent event) {
        jdbc.update(INSERT_EVENT,
                event.tenantId(),
                event.aggregateId(),
                event.eventType(),
                event.eventId(),
                event.payload());
    }
}
