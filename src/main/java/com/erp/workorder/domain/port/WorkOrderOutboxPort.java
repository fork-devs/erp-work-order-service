package com.erp.workorder.domain.port;

/**
 * The write side of the Transactional Outbox: it persists the event INSIDE
 * the caller's active transaction, next to the status change and the history
 * row. It never opens its own transaction and never publishes anything: the
 * relay (application/infrastructure) is who publishes later, with retries
 * and a dead-letter queue.
 *
 * <p>Read of pending events is NOT part of the domain port: the relay walks
 * the outbox table directly (infrastructure), because sweeping pending rows
 * across tenants is an infrastructure concern, not a domain operation.
 */
public interface WorkOrderOutboxPort {

    /**
     * Records the event in the outbox, bundled to the active transaction.
     * Empties nothing, publishes nothing, and fails the whole transaction if
     * the write fails: an event that cannot be persisted must not leave a
     * status change committed without its event.
     */
    void record(WorkOrderOutboxEvent event);

    /**
     * The transport-neutral representation recorded in the outbox: the
     * event_type column plus its JSON payload, both arbitrated by the
     * persistent adapter.
     */
    record WorkOrderOutboxEvent(
            String eventType,
            java.util.UUID eventId,
            java.util.UUID tenantId,
            java.util.UUID aggregateId,
            String payload
    ) {
    }
}
