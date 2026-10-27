package com.erp.workorder.domain.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every event carries the tenant and the order identifier, plus its own
 * event_id so a downstream consumer can deduplicate. The occurredOn time
 * travels in the payload so the consumer sees the moment of the transition,
 * not the publish time.
 */
class WorkOrderEventsTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void theCompletedEventCarriesTenantOrderAndBranch() {
        UUID eventId = UUID.randomUUID();
        WorkOrderCompleted event = new WorkOrderCompleted(
                eventId, TENANT, UUID.randomUUID(), "WO-000004",
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-22T16:00:00Z"));

        assertEquals(TENANT, event.tenantId());
        assertEquals(eventId, event.eventId());
        assertEquals("WO-000004", event.workOrderNumber());
        assertNotNull(event.occurredOn());
    }

    @Test
    void theCancelledEventCarriesPreviousStatus() {
        WorkOrderCancelled event = new WorkOrderCancelled(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), "WO-000005",
                UUID.randomUUID(), UUID.randomUUID(), "COTIZADA", Instant.now());

        assertEquals(TENANT, event.tenantId());
        assertEquals("COTIZADA", event.previousStatus());
        assertNotNull(event.workOrderNumber());
    }

    @Test
    void theEventIdIsUniquePerInstance() {
        WorkOrderCompleted first = new WorkOrderCompleted(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), "WO-000006",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        WorkOrderCompleted second = new WorkOrderCompleted(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), "WO-000006",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        assertNotEquals(first.eventId(), second.eventId(), "the event_id deduplicates consumers");
        assertTrue(first.eventId() != null && second.eventId() != null);
    }
}
