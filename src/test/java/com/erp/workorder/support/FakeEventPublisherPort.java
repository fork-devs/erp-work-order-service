package com.erp.workorder.support;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import com.erp.workorder.domain.port.EventPublisherPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake bus registered IN THE TEST CONTEXT ({@code @Primary}, the same
 * test-support shadowing as the fleet and inventory fakes). It lets the
 * tests steer the transport: a poisoned event refuses publication once or
 * always, exactly like a degraded bus, and every delivery attempt is
 * recorded for the assertions.
 */
@Component
@Primary
public class FakeEventPublisherPort implements EventPublisherPort {

    private final List<WorkOrderOutboxMessage> published = new CopyOnWriteArrayList<>();
    private final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failAlways = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> refusedCounts = new ConcurrentHashMap<>();

    @Override
    public void publish(WorkOrderOutboxMessage message) {
        UUID eventId = message.eventId();
        if (failAlways.contains(eventId)) {
            refusedCounts.merge(eventId, 1, Integer::sum);
            throw new IllegalStateException("the bus refused the event " + eventId);
        }
        if (failOnce.remove(eventId)) {
            refusedCounts.merge(eventId, 1, Integer::sum);
            throw new IllegalStateException("the bus refused the event once: " + eventId);
        }
        published.add(message);
    }

    public boolean wasPublished(UUID eventId) {
        return published.stream().anyMatch(message -> message.eventId().equals(eventId));
    }

    public List<WorkOrderOutboxMessage> publishedEvents() {
        return List.copyOf(published);
    }

    public void failOnceFor(UUID eventId) {
        failOnce.add(eventId);
    }

    public void failAlwaysFor(UUID eventId) {
        failAlways.add(eventId);
    }

    public void reset() {
        published.clear();
        failOnce.clear();
        failAlways.clear();
        refusedCounts.clear();
    }
}
