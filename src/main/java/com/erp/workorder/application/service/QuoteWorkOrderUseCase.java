package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.result.QuoteWorkOrderResult;
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
 * Quotes a work order: the registered parts are RESERVED in the inventory
 * through the saga, and ONLY when every reservation succeeded does the
 * order land at COTIZADA. Everything happens in ONE transaction:
 *
 * <ul>
 *   <li>The reservation call is made for every registered item, with the
 *   idempotency key derived from the order AND the part
 *   ({@code wo:{orderId}:reserve:{partId}}), the ORDER's branch and the
 *   orderId as the driving reference — so the inventory can arbitrate an
 *   unknown outcome with its claim.</li>
 *   <li>A rejected or unavailable reservation aborts the transaction: the
 *   order keeps EN_DIAGNOSTICO, with no history row and no quoting trace.</li>
 *   <li>An order with NO items quotes directly: pure labor needs no stock
 *   and the machine declares EN_DIAGNOSTICO -&gt; COTIZADA for it too.</li>
 * </ul>
 */
@Component
public class QuoteWorkOrderUseCase {

    private static final WorkOrderStatus DESTINATION = WorkOrderStatus.COTIZADA;

    private final WorkOrderRepositoryPort repository;
    private final InventoryPort inventory;
    private final UnitOfWork unitOfWork;

    public QuoteWorkOrderUseCase(WorkOrderRepositoryPort repository,
                                 InventoryPort inventory,
                                 UnitOfWork unitOfWork) {
        this.repository = repository;
        this.inventory = inventory;
        this.unitOfWork = unitOfWork;
    }

    public QuoteWorkOrderResult quote(QuoteWorkOrderCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> {
                        SecurityEventLogger.registrar("orden_ajena_intentada", command.tenantId(),
                                command.changedBy(), null, "rechazado",
                                Map.of("work_order_id", command.orderId().toString(),
                                        "operacion", "cotizacion"));
                        return new WorkOrderNotFoundException("the order does not exist in this tenant");
                    });

            // The machine's verdict first: no stock call leaves the service
            // for a transition it will not perform.
            order.status().requireTransitionTo(DESTINATION);

            reserveEveryItem(order);

            order.transitionTo(DESTINATION, command.changedBy(), Instant.now());
            repository.updateStatus(order);

            var transition = order.history().get(order.history().size() - 1).transition();
            return new QuoteWorkOrderResult(order.id(), order.status(), transition.origin());
        });
    }

    private void reserveEveryItem(WorkOrder order) {
        for (WorkOrderItem item : order.items()) {
            inventory.reserve(new StockReservation(
                    item.sparePart().id(),
                    order.branch().id(),
                    item.quantity(),
                    order.id(),
                    reservationKey(order.id(), item.sparePart().id())));
        }
    }

    /** The idempotency key of one part's reservation for this order. */
    public static String reservationKey(UUID orderId, UUID sparePartId) {
        return "wo:" + orderId + ":reserve:" + sparePartId;
    }
}
