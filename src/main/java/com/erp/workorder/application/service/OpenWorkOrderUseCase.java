package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.MechanicRef;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.VehicleRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.domain.port.WorkOrderSequencePort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Opens a work order. Three guarantees live here, all inside ONE
 * transactional boundary:
 *
 * <ol>
 *   <li>The vehicle is verified against fleet BEFORE any write: the vehicle
 *   must exist in the tenant and its registered customer must be the
 *   customer of the command. Fail-closed — a fleet outage does not open the
 *   order.</li>
 *   <li>The number is RESERVED, never counted: the per-tenant counter row is
 *   claimed {@code ON CONFLICT DO NOTHING} and advanced under the row lock,
 *   so two concurrent openings of the same tenant take consecutive numbers
 *   and a cancelled order's number is never reused.</li>
 *   <li>The order row and its opening history entry leave together, or not
 *   at all.</li>
 * </ol>
 */
@Component
public class OpenWorkOrderUseCase {

    private final VehicleVerificationService vehicleVerification;
    private final WorkOrderSequencePort sequence;
    private final WorkOrderRepositoryPort repository;
    private final UnitOfWork unitOfWork;

    public OpenWorkOrderUseCase(VehicleVerificationService vehicleVerification,
                                WorkOrderSequencePort sequence,
                                WorkOrderRepositoryPort repository,
                                UnitOfWork unitOfWork) {
        this.vehicleVerification = vehicleVerification;
        this.sequence = sequence;
        this.repository = repository;
        this.unitOfWork = unitOfWork;
    }

    public OpenWorkOrderResult open(OpenWorkOrderCommand command) {
        return unitOfWork.callInTransaction(() -> {
            verifyAgainstFleet(command);

            WorkOrderNumber number = sequence.claimNextNumber(command.tenantId());

            UUID orderId = UUID.randomUUID();
            Instant openedAt = Instant.now();
            WorkOrder order = WorkOrder.opened(
                    orderId,
                    new TenantId(command.tenantId()),
                    number,
                    new CustomerRef(command.customerId()),
                    new VehicleRef(command.vehicleId()),
                    new BranchId(command.branchId()),
                    command.diagnosis(),
                    openedAt,
                    command.mechanicId());

            repository.save(order);

            return new OpenWorkOrderResult(order.id(), order.number().value(), order.status(), command.tenantId());
        });
    }

    private void verifyAgainstFleet(OpenWorkOrderCommand command) {
        vehicleVerification.verifyOpening(command.tenantId(), command.vehicleId(), command.customerId());
    }
}
