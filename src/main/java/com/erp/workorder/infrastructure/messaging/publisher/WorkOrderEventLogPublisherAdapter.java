package com.erp.workorder.infrastructure.messaging.publisher;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import com.erp.workorder.domain.port.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The transport of the outbox's events, as a registration in the log:
 * there is no Kafka in the platform yet. The change introducing the bus
 * swaps this adapter, keeping the relay and the retry machinery intact.
 *
 * <p>The published event's id is logged explicitly: at-least-once delivery
 * starts being deduplicated by the consumers FROM the event_id.
 */
@Component
public class WorkOrderEventLogPublisherAdapter implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderEventLogPublisherAdapter.class);

    @Override
    public void publish(WorkOrderOutboxMessage message) {
        log.info("EVENT published: event_id={} type={} tenant_id={} order_id={} payload={}",
                message.eventId(), message.eventType(), message.tenantId(),
                message.aggregateId(), message.payload());
    }
}
