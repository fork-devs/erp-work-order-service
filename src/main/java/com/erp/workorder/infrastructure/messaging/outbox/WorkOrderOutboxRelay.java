package com.erp.workorder.infrastructure.messaging.outbox;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import com.erp.workorder.domain.port.EventPublisherPort;
import com.erp.workorder.infrastructure.persistence.outbox.WorkOrderOutboxRelayStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * The outbox relay: sweeps pending events and publishes them, one by one.
 *
 * <p>Three properties the mature outbox demands:
 *
 * <ul>
 *   <li>One failing event does NOT stop the rest of the batch: the error
 *   is confined to the message, the next one goes out.</li>
 *   <li>Every failure is REGISTERED: the attempt counter grows and the
 *   next attempt waits with a GROWING delay ({@code next_attempt_at});
 *   at the attempt limit the event lands in the dead-letter queue,
 *   consultable and reprocessable.</li>
 *   <li>The publication happens OUTSIDE the business transaction that
 *   committed the change: the event travels when the order's write is
 *   already durable, never during.</li>
 * </ul>
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "erp.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class WorkOrderOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderOutboxRelay.class);

    private final WorkOrderOutboxRelayStore store;
    private final EventPublisherPort publisher;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration maxDelay;

    public WorkOrderOutboxRelay(WorkOrderOutboxRelayStore store,
                                EventPublisherPort publisher,
                                @Value("${erp.outbox.batch-size:20}") int batchSize,
                                @Value("${erp.outbox.max-attempts:7}") int maxAttempts,
                                @Value("${erp.outbox.max-delay-seconds:86400}") long maxDelaySeconds) {
        this.store = store;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.maxDelay = Duration.ofSeconds(maxDelaySeconds);
    }

    @Scheduled(fixedDelayString = "${erp.outbox.poll-millis:5000}")
    public void sweep() {
        int published = processPending();
        if (published > 0) {
            log.debug("The relay published {} pending events", published);
        }
    }

    /**
     * One sweep of the pending batch. Returns how many events left the
     * queue in this sweep; a poisoned event's failure never breaks the
     * loop, so the healthy ones keep flowing and the poisoned ones age
     * with their growing delay.
     */
    public int processPending() {
        List<WorkOrderOutboxMessage> claimed = store.claimPendingBatch(batchSize);
        int published = 0;
        for (WorkOrderOutboxMessage message : claimed) {
            try {
                publisher.publish(message);
                message.markAsPublished();
                published++;
            } catch (RuntimeException failure) {
                // The SAME event_id survives the attempts: the consumer's
                // deduplication never sees a second identity.
                log.warn("The event could not be published, it will be retried: event_id={} attempt={} cause={}",
                        message.eventId(), message.attempts() + 1, failure.getMessage());
                message.recordFailure(failure.getMessage(), maxAttempts, maxDelay);
            }
            store.saveAttemptOutcome(message);
        }
        return published;
    }

}
