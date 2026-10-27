package com.erp.workorder.interfaces.rest.admin;

import com.erp.workorder.domain.model.WorkOrderOutboxMessage;
import com.erp.workorder.infrastructure.persistence.outbox.WorkOrderOutboxRelayStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dead-letter queue of the outbox, consultable and reprocessable by
 * the platform's SUPERADMIN: a poisoned event is never dropped, it waits
 * here with its attempt history until an operator reads it and returns it
 * to the pending queue, where the relay picks it on its next sweep.
 *
 * <p>The queue is the service's INTERNAL one (no tenant isolation by
 * design, like the inventory's); the role check is the only gate.
 *
 * <p>The {@code @PreAuthorize} is enforced from group 8, when the security
 * filter chain and the method security turn on; the annotation is already
 * part of the contract.
 */
@RestController
@RequestMapping("/api/v1/work-orders/admin/outbox")
@PreAuthorize("hasRole('SUPERADMIN')")
public class WorkOrderOutboxAdminController {

    private final WorkOrderOutboxRelayStore store;

    public WorkOrderOutboxAdminController(WorkOrderOutboxRelayStore store) {
        this.store = store;
    }

    /** The dead-lettered events, newest first. */
    @GetMapping
    public List<Map<String, Object>> deadLettered() {
        return store.deadLettered(100).stream()
                .map(this::view)
                .toList();
    }

    /** Returns one dead-lettered event to the pending queue by its event_id. */
    @PostMapping("/{eventId}/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable UUID eventId) {
        if (!store.requeue(eventId)) {
            return ResponseEntity.notFound().build();
        }
        WorkOrderOutboxMessage message = store.byEventId(eventId).orElseThrow();
        return ResponseEntity.accepted().body(Map.of(
                "eventId", message.eventId().toString(),
                "estado", "PENDIENTE",
                "attempts", message.attempts()));
    }

    private Map<String, Object> view(WorkOrderOutboxMessage message) {
        return Map.ofEntries(
                Map.entry("eventId", message.eventId().toString()),
                Map.entry("tenantId", message.tenantId().toString()),
                Map.entry("orderId", message.aggregateId().toString()),
                Map.entry("eventType", message.eventType()),
                Map.entry("attempts", message.attempts()),
                Map.entry("createdAt", message.createdAt().toString()));
    }
}
