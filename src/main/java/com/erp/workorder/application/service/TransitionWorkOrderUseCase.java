package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.result.TransitionWorkOrderResult;
import com.erp.workorder.application.exception.SagaReservedTransitionException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.application.logging.SecurityEventLogger;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderOutboxPort;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Applies one transition of the state machine declared in
 * {@code WorkOrderStatus}. The transition is explicit or nothing: a move
 * the machine does not declare rejects with
 * {@code InvalidWorkOrderTransitionException} and does NOT write status nor
 * history — the database stays exactly as it was.
 *
 * <p>The saga-facing transitions (quote, approve, reject, cancel) live in
 * their own use cases; this one carries the moves that need no inventory
 * call and is the fallback the machine itself arbitrates.
 */
@Component
public class TransitionWorkOrderUseCase {

    private final WorkOrderRepositoryPort repository;
    private final WorkOrderOutboxPort outbox;
    private final WorkOrderOutboxEventFactory events;
    private final UnitOfWork unitOfWork;

    public TransitionWorkOrderUseCase(WorkOrderRepositoryPort repository,
                                      WorkOrderOutboxPort outbox,
                                      WorkOrderOutboxEventFactory events,
                                      UnitOfWork unitOfWork) {
        this.repository = repository;
        this.outbox = outbox;
        this.events = events;
        this.unitOfWork = unitOfWork;
    }

    public TransitionWorkOrderResult transition(TransitionWorkOrderCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> {
                        SecurityEventLogger.registrar("orden_ajena_intentada", command.tenantId(),
                                command.changedBy(), null, "rechazado",
                                Map.of("work_order_id", command.orderId().toString(),
                                        "operacion", "transicion"));
                        return new WorkOrderNotFoundException("the order does not exist in this tenant");
                    });

            rejectReservedSagaTransitions(order.status(), command.destination());

            order.transitionTo(command.destination(), command.changedBy(), Instant.now());

            repository.updateStatus(order);
            recordOutboxEvent(command, order);

            // After a successful transitionTo the last history entry always
            // carries its transition; its origin is the status before the move.
            var transition = order.history().get(order.history().size() - 1).transition();

            return new TransitionWorkOrderResult(
                    order.id(),
                    order.status(),
                    transition.origin(),
                    order.closedAt().orElse(null));
        });
    }

    /**
     * The delivery and the cancellation leave the outbox inside the SAME
     * transaction: the event row and the history row commit together, so a
     * consumer never misses the status change and the bus never receives
     * one the status did not confirm.
     */
    private void recordOutboxEvent(TransitionWorkOrderCommand command, WorkOrder order) {
        if (command.destination() == WorkOrderStatus.ENTREGADA) {
            outbox.record(events.completed(command.tenantId(), order.id(), Instant.now()));
        } else if (command.destination() == WorkOrderStatus.CANCELADA) {
            outbox.record(events.cancelled(command.tenantId(), order.id(), Instant.now()));
        }
    }

    /**
     * The machine may declare a transition, but when its baggage is the
     * stock saga it runs ONLY through its own use case. The generic door
     * refuses it here — BEFORE any write — so nobody bypasses the
     * reservation by calling the free path.
     */
    private void rejectReservedSagaTransitions(WorkOrderStatus origin, WorkOrderStatus destination) {
        if (origin == WorkOrderStatus.EN_DIAGNOSTICO && destination == WorkOrderStatus.COTIZADA) {
            throw new SagaReservedTransitionException(
                    "the quotation reserves the stock through its own use case: the generic transition "
                            + "does not bypass the reservation");
        }
        if (origin == WorkOrderStatus.COTIZADA && destination == WorkOrderStatus.EN_EJECUCION) {
            throw new SagaReservedTransitionException(
                    "the approval commits the reserved stock through its own use case: the generic transition "
                            + "does not bypass the consumption");
        }
        if (origin == WorkOrderStatus.COTIZADA
                && (destination == WorkOrderStatus.RECHAZADA || destination == WorkOrderStatus.CANCELADA)) {
            throw new SagaReservedTransitionException(
                    "the rejection or the cancellation of a quoted order releases the reserved stock through its "
                            + "own use cases: the generic transition does not bypass the release");
        }
    }
}
