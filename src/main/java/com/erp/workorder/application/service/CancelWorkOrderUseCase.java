package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.CancelWorkOrderCommand;
import com.erp.workorder.application.dto.result.CancelWorkOrderResult;
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
 * Cancels a work order. The stock rule is conditional and explicit: the
 * reserved parts are RELEASED only when the cancellation breaks a LIVE
 * reservation (the order was at COTIZADA). A cancellation of an order
 * that never quoted has NOTHING to return — the inventory is not called
 * with release keys for reservations that do not exist.
 */
@Component
public class CancelWorkOrderUseCase {

    private static final WorkOrderStatus DESTINATION = WorkOrderStatus.CANCELADA;

    private final WorkOrderRepositoryPort repository;
    private final ReservationReleaseService release;
    private final UnitOfWork unitOfWork;

    public CancelWorkOrderUseCase(WorkOrderRepositoryPort repository,
                                  ReservationReleaseService release,
                                  UnitOfWork unitOfWork) {
        this.repository = repository;
        this.release = release;
        this.unitOfWork = unitOfWork;
    }

    public CancelWorkOrderResult cancel(CancelWorkOrderCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> {
                        SecurityEventLogger.registrar("orden_ajena_intentada", command.tenantId(),
                                command.changedBy(), null, "rechazado",
                                Map.of("work_order_id", command.orderId().toString(),
                                        "operacion", "cancelacion"));
                        return new WorkOrderNotFoundException("the order does not exist in this tenant");
                    });

            // The machine's verdict first: a cancellation from EN_EJECUCION
            // or a terminal state does NOT move and, this way, NEVER tries
            // to release what is being consumed.
            order.status().requireTransitionTo(DESTINATION);

            if (ReservationReleaseService.reservationIsAlive(order.status())) {
                release.releaseReservationOf(order);
            }

            order.transitionTo(DESTINATION, command.changedBy(), Instant.now());
            repository.updateStatus(order);

            var transition = order.history().get(order.history().size() - 1).transition();
            return new CancelWorkOrderResult(order.id(), order.status(), transition.origin());
        });
    }
}
