package com.erp.workorder.application.service;

import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderItem;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.InventoryPort;
import com.erp.workorder.domain.port.InventoryPort.StockReservation;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Releases the reserved stock of an order whose quotation did not end in
 * a consumption: the client rejected the quote or the order was cancelled
 * while the reservation was still alive. Every item travels with its own
 * idempotency key ({@code wo:{orderId}:release:{partId}}), so the release
 * repeated RETURNS NOTHING TWICE — the inventory's claim dedups it.
 *
 * <p>The release NEVER runs for a consumed reservation (EN_EJECUCION or
 * later): the inventory cannot undo a consumption and the call would fake
 * a return of stock that never came back.
 */
@Component
public class ReservationReleaseService {

    private final InventoryPort inventory;

    public ReservationReleaseService(InventoryPort inventory) {
        this.inventory = inventory;
    }

    /**
     * Whether the reservation is still alive: the reservation only lives
     * at COTIZADA — quoted (reserved) but not yet consumed.
     */
    public static boolean reservationIsAlive(WorkOrderStatus currentStatus) {
        return currentStatus == WorkOrderStatus.COTIZADA;
    }

    /** Releases every part's reservation of this order, once per part. */
    public void releaseReservationOf(WorkOrder order) {
        for (WorkOrderItem item : order.items()) {
            inventory.release(new StockReservation(
                    item.sparePart().id(),
                    order.branch().id(),
                    item.quantity(),
                    order.id(),
                    releaseKey(order.id(), item.sparePart().id())));
        }
    }

    /** The idempotency key of one part's release for this order. */
    public static String releaseKey(UUID orderId, UUID sparePartId) {
        return "wo:" + orderId + ":release:" + sparePartId;
    }
}
