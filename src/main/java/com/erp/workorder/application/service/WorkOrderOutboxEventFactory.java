package com.erp.workorder.application.service;

import com.erp.workorder.domain.port.WorkOrderOutboxPort;
import com.erp.workorder.domain.port.WorkOrderOutboxPort.WorkOrderOutboxEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the work order's outbox events: ONE identity per real transition.
 * The payload carries the tenant's and the order's identifiers — the two
 * coordinates the hub needs to route the event — plus the status the order
 * reached and the instant of the transition. The builder serializes with
 * controlled values only (UUIDs and instants are JSON-safe by nature).
 */
@Component
public class WorkOrderOutboxEventFactory {

    public static final String COMPLETED = "WorkOrderCompleted";
    public static final String CANCELLED = "WorkOrderCancelled";

    private final UUID instanceSalt = UUID.randomUUID();

    /** A deterministic UUIDv5-like identity: deduplication per transition. */
    private UUID newEventId(UUID orderId, String kind) {
        return UUID.nameUUIDFromBytes((kind + ":" + orderId + ":" + instanceSalt).getBytes());
    }

    /** The order's vehicle was delivered back to its customer. */
    public WorkOrderOutboxEvent completed(UUID tenantId, UUID orderId, Instant occurredAt) {
        return new WorkOrderOutboxEvent(COMPLETED, newEventId(orderId, COMPLETED), tenantId,
                orderId, payloadJson(COMPLETED, tenantId, orderId, "ENTREGADA", occurredAt));
    }

    /** The order was cancelled before or without its stock consumption. */
    public WorkOrderOutboxEvent cancelled(UUID tenantId, UUID orderId, Instant occurredAt) {
        return new WorkOrderOutboxEvent(CANCELLED, newEventId(orderId, CANCELLED), tenantId,
                orderId, payloadJson(CANCELLED, tenantId, orderId, "CANCELADA", occurredAt));
    }

    private String payloadJson(String eventType, UUID tenantId, UUID orderId,
                               String status, Instant occurredAt) {
        return "{\"eventType\":\"" + eventType
                + "\",\"tenantId\":\"" + tenantId
                + "\",\"orderId\":\"" + orderId
                + "\",\"status\":\"" + status
                + "\",\"occurredAt\":\"" + occurredAt
                + "\"}";
    }
}
