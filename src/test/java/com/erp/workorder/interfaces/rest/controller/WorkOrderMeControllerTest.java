package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.domain.model.BranchId;
import com.erp.workorder.domain.model.CustomerRef;
import com.erp.workorder.domain.model.TenantId;
import com.erp.workorder.domain.model.VehicleRef;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderNumber;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.interfaces.rest.dto.response.WorkOrderSummaryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkOrderMeControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void listMyWorkOrdersReturnsOnlyOrdersBelongingToAuthenticatedUser() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        WorkOrder ownOrder = WorkOrder.rehydrated(
                UUID.randomUUID(), new TenantId(tenantId), new WorkOrderNumber(1),
                new CustomerRef(userId), new VehicleRef(UUID.randomUUID()), new BranchId(UUID.randomUUID()),
                null, WorkOrderStatus.RECEIVED, "Brake check", Instant.now(), null, List.of(), List.of()
        );
        WorkOrder otherOrder = WorkOrder.rehydrated(
                UUID.randomUUID(), new TenantId(tenantId), new WorkOrderNumber(2),
                new CustomerRef(otherUserId), new VehicleRef(UUID.randomUUID()), new BranchId(UUID.randomUUID()),
                null, WorkOrderStatus.RECEIVED, "Oil change", Instant.now(), null, List.of(), List.of()
        );

        WorkOrderRepositoryPort repo = mock(WorkOrderRepositoryPort.class);
        when(repo.listByTenant(tenantId, null)).thenReturn(List.of(ownOrder, otherOrder));

        UnitOfWork uow = mock(UnitOfWork.class);
        when(uow.callInTransaction(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), tenantId.toString(), List.of())
        );

        WorkOrderController controller = new WorkOrderController(
                null, null, null, null, null, null, null, repo, uow
        );

        ResponseEntity<List<WorkOrderSummaryResponse>> res = controller.listMyWorkOrders();
        assertEquals(200, res.getStatusCode().value());
        assertEquals(1, res.getBody().size());
        assertEquals(ownOrder.id(), res.getBody().get(0).orderId());
    }
}
