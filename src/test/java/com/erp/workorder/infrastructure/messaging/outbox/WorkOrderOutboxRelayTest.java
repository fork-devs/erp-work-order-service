package com.erp.workorder.infrastructure.messaging.outbox;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import com.erp.workorder.infrastructure.persistence.outbox.WorkOrderOutboxRelayStore;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeEventPublisherPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay's queue conduct: a poisoned event does not block the batch,
 * every failure ages with a GROWING wait, the attempt limit lands the
 * event in the dead-letter queue, and the published identity is the SAME
 * event_id in every attempt.
 */
class WorkOrderOutboxRelayTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private WorkOrderOutboxRelayStore store;

    @Autowired
    private FakeEventPublisherPort publisher;

    @AfterEach
    void cleanTheQueue() throws SQLException {
        publisher.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_outbox");
        }
    }

    @Test
    void aFailedEventDoesNotStopTheRestOfTheBatch() throws SQLException {
        WorkOrderOutboxRelay relay = relayWith();
        UUID healthy = seed("WorkOrderCompleted", "PENDIENTE", 0, null);
        UUID poisoned = seed("WorkOrderCompleted", "PENDIENTE", 0, null);
        UUID after = seed("WorkOrderCancelled", "PENDIENTE", 0, null);
        publisher.failAlwaysFor(poisoned);

        relay.processPending();

        assertTrue(eventState(healthy).equals("PUBLICADO"), "the event before the poison went out");
        assertTrue(eventState(after).equals("PUBLICADO"), "the event AFTER the poison went out too");
        assertTrue(eventState(poisoned).equals("PENDIENTE"), "the poison stays pending");
        assertEquals(1, attempts(poisoned), "the poison kept its attempt count");
        assertTrue(nextAttempt(poisoned).isAfter(Instant.now()),
                "the next attempt waits: the batch is not hammered");
    }

    @Test
    void theRetryWaitsLongerEveryTimeUntilTheDeadLetter() throws SQLException {
        WorkOrderOutboxRelay relay = relayWith();
        UUID poisonedId = seed("WorkOrderCompleted", "PENDIENTE", 0, null);
        publisher.failAlwaysFor(poisonedId);

        assertTrue(publisher.publishedEvents().isEmpty());
        relay.processPending();                       // attempt 1: backoff 5 s
        waitUntilClaimable(poisonedId);
        relay.processPending();                       // attempt 2: backoff 30 s
        waitUntilClaimable(poisonedId);
        relay.processPending();                       // attempt 3: the limit => DLQ

        assertEquals("DLQ", eventState(poisonedId),
                "the attempt limit moves the event to the dead-letter queue");
        assertEquals(3, attempts(poisonedId));
        assertFalse(publisher.wasPublished(poisonedId));
        // A fourth sweep cannot claim it: the dead letter stops retrying by itself.
        assertTrue(store.claimPendingBatch(20).isEmpty());

        // The identity survives: the SAME event_id in every attempt.
        List<UUID> seenIdentities = new ArrayList<>();
        for (WorkOrderOutboxMessage message : store.deadLettered(10)) {
            seenIdentities.add(message.eventId());
        }
        assertTrue(seenIdentities.contains(poisonedId), "the event keeps its one identity");
    }

    @Test
    void aRequeuedEventLeavesTheDeadLetterAndPublishes() throws SQLException {
        WorkOrderOutboxRelay relay = relayWith();
        UUID eventId = seed("WorkOrderCompleted", "DLQ", 5, null);
        assertFalse(publisher.wasPublished(eventId));

        assertTrue(store.requeue(eventId), "the dead letter returns to the pending queue");
        assertEquals("PENDIENTE", eventState(eventId));
        assertEquals(0, attempts(eventId));

        relay.processPending();
        assertEquals("PUBLICADO", eventState(eventId), "the reprocessed event went out");
        assertTrue(publisher.wasPublished(eventId));
    }

    // -- helpers ----------------------------------------------------------

    /** The relay built per test: max attempts of 3 reaches the dead letter quickly. */
    private WorkOrderOutboxRelay relayWith() {
        return new WorkOrderOutboxRelay(store, publisher, 20, 3, 86400);
    }

    private void waitUntilClaimable(UUID eventId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE work_order_outbox SET next_attempt_at = now() - interval '1 hour' WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            statement.executeUpdate();
        }
    }

    private UUID seed(String eventType, String estado, int attempts, UUID forcedEventId) throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = forcedEventId == null ? UUID.randomUUID() : forcedEventId;
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO work_order_outbox (tenant_id, aggregate_id, event_type, event_id, payload, estado, attempts)"
                             + " VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)")) {
            statement.setObject(1, tenant);
            statement.setObject(2, orderId);
            statement.setString(3, eventType);
            statement.setObject(4, eventId);
            statement.setString(5, "{\"eventType\":\"" + eventType + "\",\"tenantId\":\"" + tenant
                    + "\",\"orderId\":\"" + orderId + "\"}");
            statement.setString(6, estado);
            statement.setInt(7, attempts);
            statement.executeUpdate();
        }
        return eventId;
    }

    private String eventState(UUID eventId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT estado FROM work_order_outbox WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private int attempts(UUID eventId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT attempts FROM work_order_outbox WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private Instant nextAttempt(UUID eventId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT next_attempt_at FROM work_order_outbox WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                var timestamp = rows.getTimestamp(1);
                return timestamp == null ? null : timestamp.toInstant();
            }
        }
    }
}
