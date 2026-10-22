package com.erp.workorder.infrastructure.persistence.adapter;

import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.port.WorkOrderSequencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The per-tenant counter claim, on raw SQL because the two statements are
 * deliberately non-expressible in JPA: {@code ON CONFLICT DO NOTHING} (a
 * tenant with no previous orders gets its row without a race) and
 * {@code UPDATE ... RETURNING} (the advance returns the number taken under
 * the {@code FOR UPDATE}-equivalent row lock the UPDATE holds).
 *
 * <p>Both statements join the transaction the application layer opened:
 * the RLS aspect established the tenant context, and a rollback of the
 * opening rolls the counter advance back with it.
 */
@Component
public class WorkOrderSequencePersistenceAdapter implements WorkOrderSequencePort {

    private final JdbcTemplate jdbcTemplate;

    public WorkOrderSequencePersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkOrderNumber claimNextNumber(UUID tenantId) {
        jdbcTemplate.update(
                "INSERT INTO work_order_sequences (tenant_id, last_number) VALUES (?, 0) "
                        + "ON CONFLICT (tenant_id) DO NOTHING",
                tenantId);
        Integer taken = jdbcTemplate.queryForObject(
                "UPDATE work_order_sequences SET last_number = last_number + 1, updated_at = now() "
                        + "WHERE tenant_id = ? RETURNING last_number",
                Integer.class,
                tenantId);
        if (taken == null) {
            throw new IllegalStateException("the tenant counter did not yield a number");
        }
        return WorkOrderNumber.of(taken);
    }
}
