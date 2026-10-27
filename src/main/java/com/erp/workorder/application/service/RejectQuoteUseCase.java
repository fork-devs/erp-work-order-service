package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.RejectQuoteCommand;
import com.erp.workorder.application.dto.result.RejectQuoteResult;
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
 * The client does not approve the quotation: every reserved part is
 * RELEASED back into the inventory's lots and only then the order lands
 * at RECHAZADA. The machine only declares this move from COTIZADA — the
 * state where the reservation is alive — so the rejection ALWAYS has
 * stock to give back and the release is never skipped.
 */
@Component
public class RejectQuoteUseCase {

    private static final WorkOrderStatus DESTINATION = WorkOrderStatus.RECHAZADA;

    private final WorkOrderRepositoryPort repository;
    private final ReservationReleaseService release;
    private final UnitOfWork unitOfWork;

    public RejectQuoteUseCase(WorkOrderRepositoryPort repository,
                              ReservationReleaseService release,
                              UnitOfWork unitOfWork) {
        this.repository = repository;
        this.release = release;
        this.unitOfWork = unitOfWork;
    }

    public RejectQuoteResult reject(RejectQuoteCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> {
                        SecurityEventLogger.registrar("orden_ajena_intentada", command.tenantId(),
                                command.changedBy(), null, "rechazado",
                                Map.of("work_order_id", command.orderId().toString(),
                                        "operacion", "rechazo"));
                        return new WorkOrderNotFoundException("the order does not exist in this tenant");
                    });

            order.status().requireTransitionTo(DESTINATION);
            release.releaseReservationOf(order);

            order.transitionTo(DESTINATION, command.changedBy(), Instant.now());
            repository.updateStatus(order);

            var transition = order.history().get(order.history().size() - 1).transition();
            return new RejectQuoteResult(order.id(), order.status(), transition.origin());
        });
    }
}
