package com.erp.workorder.domain.port;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;

/**
 * The transport of the outbox's events. There is no Kafka in the platform
 * yet: the adapter registers the published event (log), and the change
 * introducing the bus swaps the adapter without touching the relay or the
 * use cases.
 */
public interface EventPublisherPort {

    void publish(WorkOrderOutboxMessage message);
}
