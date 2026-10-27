package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.result.ApproveQuoteResult;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.application.logging.SecurityEventLogger;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderItem;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.InventoryPort.StockReservation;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Approves a quotation: every reserved part is CONSUMED in the inventory
 * and ONLY then does the order land at EN_EJECUCION. Everything happens
 * in ONE transaction:
 *
 * <ul>
 *   <li>The commit call is made for every registered item with the
 *   idempotency key derived from the order AND the part
 *   ({@code wo:{orderId}:commit:{partId}}) — a repeated approval claims
 *   the key once and consumes once.</li>
 *   <li>A rejected or unavailable commit aborts the transaction: the order
 *   keeps COTIZADA with its live reservation, with no history row.</li>
 *   <li>Consuming is NOT undone: the inventory does not un-consume, so the
 *   approval only happens from COTIZADA, while the reservation is alive.</li>
 * </ul>
 */
@Component
public class ApproveQuoteUseCase {

    private static final WorkOrderStatus DESTINATION = WorkOrderStatus.EN_EJECUCION;

    private final WorkOrderRepositoryPort repository;
    private final InventoryPort inventory;
    private final UnitOfWork unitOfWork;

    public ApproveQuoteUseCase(WorkOrderRepositoryPort repository,
                               InventoryPort inventory,
                               UnitOfWork unitOfWork) {
        this.repository = repository;
        this.inventory = inventory;
        this.unitOfWork = unitOfWork;
    }

    public ApproveQuoteResult approve(ApproveQuoteCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> {
                        SecurityEventLogger.registrar("orden_ajena_intentada", command.tenantId(),
                                command.changedBy(), null, "rechazado",
                                Map.of("work_order_id", command.orderId().toString(),
                                        "operacion", "aprobacion"));
                        return new WorkOrderNotFoundException("the order does not exist in this tenant");
                    });

            // The machine's verdict first: no commit call leaves the service
            // for a transition it will not perform.
            order.status().requireTransitionTo(DESTINATION);

            consumeEveryReservedItem(order);

            order.transitionTo(DESTINATION, command.changedBy(), Instant.now());
            repository.updateStatus(order);

            var transition = order.history().get(order.history().size() - 1).transition();
            return new ApproveQuoteResult(order.id(), order.status(), transition.origin());
        });
    }

    private void consumeEveryReservedItem(WorkOrder order) {
        for (WorkOrderItem item : order.items()) {
            inventory.commit(new StockReservation(
                    item.sparePart().id(),
                    order.branch().id(),
                    item.quantity(),
                    order.id(),
                    commitKey(order.id(), item.sparePart().id())));
        }
    }

    /** The idempotency key of one part's commit for this order. */
    public static String commitKey(UUID orderId, UUID sparePartId) {
        return "wo:" + orderId + ":commit:" + sparePartId;
    }
}
