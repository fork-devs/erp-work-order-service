package com.erp.workorder.infrastructure.persistence.outbox;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The relay's read side of the outbox table. It does NOT live in the
 * {@code persistence.adapter} package deliberately: the relay sweeps the
 * pending events of ALL tenants — it cannot know each event's tenant
 * before reading it — so the per-tenant RLS aspect must not demand a
 * tenant context here. The outbox is the service's internal queue (the
 * same criterion of the inventory's {@code stock_outbox}); the sweep runs
 * in its OWN short transactions so the claim of a row dies with the
 * session that read it, never with a business transaction.
 */
@Component
public class WorkOrderOutboxRelayStore {

    private static final String CLAIM_PENDING = """
            SELECT id, tenant_id, aggregate_id, event_type, event_id, payload,
                   estado, attempts, next_attempt_at, created_at
            FROM work_order_outbox
            WHERE estado = 'PENDIENTE'
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String UPDATE_STATE = """
            UPDATE work_order_outbox
            SET estado = ?, attempts = ?, next_attempt_at = ?
            WHERE id = ?
            """;

    private static final String SELECT_DEAD_LETTERED = """
            SELECT id, tenant_id, aggregate_id, event_type, event_id, payload,
                   estado, attempts, next_attempt_at, created_at
            FROM work_order_outbox
            WHERE estado = 'DLQ'
            ORDER BY created_at DESC
            LIMIT ?
            """;

    private static final String REQUEUE_BY_EVENT_ID = """
            UPDATE work_order_outbox
            SET estado = 'PENDIENTE', attempts = 0, next_attempt_at = NULL
            WHERE event_id = ? AND estado = 'DLQ'
            """;

    private static final RowMapper<WorkOrderOutboxMessage> MESSAGE_MAPPER = (rs, rowNum) -> {
        Timestamp nextAttempt = rs.getTimestamp("next_attempt_at");
        return new WorkOrderOutboxMessage(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("aggregate_id")),
                rs.getString("event_type"),
                rs.getString("payload"),
                WorkOrderOutboxMessage.OutboxState.valueOf(stateInEnglish(rs.getString("estado"))),
                rs.getInt("attempts"),
                nextAttempt == null ? null : nextAttempt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    };

    /** The persisted states are the table's Spanish labels; the domain enum is English. */
    private static String stateInEnglish(String persisted) {
        return switch (persisted) {
            case "PENDIENTE" -> "PENDING";
            case "PUBLICADO" -> "PUBLISHED";
            case "DLQ" -> "DEAD_LETTER";
            default -> throw new IllegalStateException("unknown outbox state: " + persisted);
        };
    }

    /** The domain enum back to the persisted label the table's CHECK admits. */
    public static String persistedState(WorkOrderOutboxMessage.OutboxState state) {
        return switch (state) {
            case PENDING -> "PENDIENTE";
            case PUBLISHED -> "PUBLICADO";
            case DEAD_LETTER -> "DLQ";
        };
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WorkOrderOutboxRelayStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Claims a batch of pending events ready for their attempt: the rows
     * are locked FOR UPDATE SKIP LOCKED inside the store's own short
     * transaction, so two relay instances never take the same row and a
     * competing lock waits instead of dying.
     */
    public List<WorkOrderOutboxMessage> claimPendingBatch(int limit) {
        List<WorkOrderOutboxMessage> claimed = transactions.execute(status ->
                jdbc.query(CLAIM_PENDING, MESSAGE_MAPPER, limit));
        return claimed == null ? List.of() : claimed;
    }

    /**
     * Writes the attempt's outcome. One short transaction per message: a
     * failing message never bundles the whole batch's progress.
     */
    public void saveAttemptOutcome(WorkOrderOutboxMessage message) {
        transactions.executeWithoutResult(status -> jdbc.update(UPDATE_STATE, ps -> {
            ps.setString(1, persistedState(message.state()));
            ps.setInt(2, message.attempts());
            if (message.nextAttemptAt() == null) {
                ps.setNull(3, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(3, Timestamp.from(message.nextAttemptAt()));
            }
            ps.setObject(4, message.rowId(), Types.OTHER);
        }));
    }

    /** The dead-letter queue, newest first, for the admin's consultation. */
    public List<WorkOrderOutboxMessage> deadLettered(int limit) {
        return jdbc.query(SELECT_DEAD_LETTERED, MESSAGE_MAPPER, limit);
    }

    /** Returns one dead-lettered event to the pending queue by its event_id. */
    public boolean requeue(UUID eventId) {
        Integer requeued = transactions.execute(status ->
                jdbc.update(REQUEUE_BY_EVENT_ID, ps -> ps.setObject(1, eventId, Types.OTHER)));
        return requeued != null && requeued > 0;
    }

    public Optional<WorkOrderOutboxMessage> byEventId(UUID eventId) {
        return jdbc.query("""
                        SELECT id, tenant_id, aggregate_id, event_type, event_id, payload,
                               estado, attempts, next_attempt_at, created_at
                        FROM work_order_outbox WHERE event_id = ?
                        """, MESSAGE_MAPPER, eventId)
                .stream().findFirst();
    }
}
