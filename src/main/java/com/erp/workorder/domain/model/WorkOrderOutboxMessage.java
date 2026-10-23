package com.erp.workorder.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A work order event held by the Transactional Outbox, pending publication.
 * Purely domain: the state machine of the QUEUE (pending, published,
 * dead-lettered), the attempt counter and the growing backoff live here,
 * testable without any transport.
 *
 * <p>The {@code eventId} travels INSIDE the message and is never
 * regenerated between retries — that is what lets a consumer deduplicate:
 * the delivery is at-least-once, and the stable identifier is what makes
 * the deduplication possible.
 */
public class WorkOrderOutboxMessage {

    /**
     * The backoff scale between retries, in seconds. It grows with every
     * attempt and caps at 24 hours, the recovery window planned for a
     * degraded dependency.
     */
    public static final long[] BACKOFF_SECONDS = {5, 30, 300, 1800, 7200, 28800, 86400};

    public enum OutboxState {
        PENDING, PUBLISHED, DEAD_LETTER
    }

    private final UUID rowId;
    private final UUID eventId;
    private final UUID tenantId;
    private final UUID aggregateId;
    private final String eventType;
    private final String payload;
    private final Instant createdAt;
    private OutboxState state;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;

    public WorkOrderOutboxMessage(UUID rowId, UUID eventId, UUID tenantId, UUID aggregateId,
                                  String eventType, String payload, OutboxState state,
                                  int attempts, Instant nextAttemptAt, Instant createdAt) {
        this.rowId = rowId;
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.state = state;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    /** The event was delivered: the claim is closed and the row stops pending. */
    public void markAsPublished() {
        this.state = OutboxState.PUBLISHED;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /**
     * Records a publication failure: the counter grows, the error is kept
     * and the next retry is scheduled with a GROWING delay. When the
     * attempt limit is reached the event moves to the dead-letter queue
     * and stops retrying by itself, but it is never dropped.
     */
    public void recordFailure(String error, int maxAttempts, Duration maxDelay) {
        this.attempts++;
        this.lastError = error;
        if (this.attempts >= maxAttempts) {
            this.state = OutboxState.DEAD_LETTER;
            this.nextAttemptAt = null;
            return;
        }
        this.state = OutboxState.PENDING;
        this.nextAttemptAt = Instant.now().plus(nextDelay(maxDelay));
    }

    /**
     * Returns the event to the pending queue with the counter reset, so it
     * is attempted again.
     */
    public void reactivate() {
        this.attempts = 0;
        this.lastError = null;
        this.state = OutboxState.PENDING;
        this.nextAttemptAt = Instant.now();
    }

    /** The wait until the next attempt: growing and capped at the max configured. */
    public Duration nextDelay(Duration maxDelay) {
        int index = Math.min(attempts, BACKOFF_SECONDS.length) - 1;
        long seconds = BACKOFF_SECONDS[Math.max(index, 0)];
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    public boolean isPublished() {
        return state == OutboxState.PUBLISHED;
    }

    public boolean isDeadLettered() {
        return state == OutboxState.DEAD_LETTER;
    }

    public UUID rowId() {
        return rowId;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public OutboxState state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
