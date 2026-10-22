package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.SparePartRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderItem;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Adds parts to an order that has not quoted yet. The part is a LOGICAL
 * reference: a spare part the inventory does not know is registered anyway
 * (spec); the guarantee of its existence and of its stock arrives with the
 * saga reservation at the quote, not with the registration.
 *
 * <p>The aggregate rejects the items when the order already quoted: after
 * {@code COTIZADA} the stock guarantee was claimed by the reservation, and
 * a terminal state accepts nothing.
 */
@Component
public class AddItemsToWorkOrderUseCase {

    private final WorkOrderRepositoryPort repository;
    private final UnitOfWork unitOfWork;

    public AddItemsToWorkOrderUseCase(WorkOrderRepositoryPort repository, UnitOfWork unitOfWork) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
    }

    /** The identifiers of the parts the order carries after the addition. */
    public List<UUID> addItems(AddItemsToWorkOrderCommand command) {
        return unitOfWork.callInTransaction(() -> {
            WorkOrder order = repository.findById(command.tenantId(), command.orderId())
                    .orElseThrow(() -> new WorkOrderNotFoundException(
                            "the order does not exist in this tenant"));

            for (WorkOrderItemCommand item : command.items()) {
                order.addItem(new WorkOrderItem(
                        new SparePartRef(item.sparePartId()),
                        item.quantity(),
                        item.laborPrice()));
            }

            repository.updateStatus(order);

            return order.items().stream().map(item -> item.sparePart().id()).toList();
        });
    }
}
