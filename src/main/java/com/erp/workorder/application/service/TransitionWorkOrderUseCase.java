package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.result.TransitionWorkOrderResult;
import com.erp.workorder.application.exception.SagaReservedTransitionException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.application.logging.SecurityEventLogger;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.UnitOfWork;
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
    private final UnitOfWork unitOfWork;

    public TransitionWorkOrderUseCase(WorkOrderRepositoryPort repository, UnitOfWork unitOfWork) {
        this.repository = repository;
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
    }
}
