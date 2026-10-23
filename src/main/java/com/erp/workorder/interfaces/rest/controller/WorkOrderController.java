package com.erp.workorder.interfaces.rest.controller;

import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.ApproveQuoteCommand;
import com.erp.workorder.application.dto.command.CancelWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.QuoteWorkOrderCommand;
import com.erp.workorder.application.dto.command.RejectQuoteCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.ApproveQuoteResult;
import com.erp.workorder.application.dto.result.CancelWorkOrderResult;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.dto.result.QuoteWorkOrderResult;
import com.erp.workorder.application.dto.result.RejectQuoteResult;
import com.erp.workorder.application.dto.result.TransitionWorkOrderResult;
import com.erp.workorder.application.service.AddItemsToWorkOrderUseCase;
import com.erp.workorder.application.service.ApproveQuoteUseCase;
import com.erp.workorder.application.service.CancelWorkOrderUseCase;
import com.erp.workorder.application.service.OpenWorkOrderUseCase;
import com.erp.workorder.application.service.QuoteWorkOrderUseCase;
import com.erp.workorder.application.service.RejectQuoteUseCase;
import com.erp.workorder.application.service.TransitionWorkOrderUseCase;
import com.erp.workorder.domain.model.WorkOrder;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.domain.port.UnitOfWork;
import com.erp.workorder.domain.port.WorkOrderRepositoryPort;
import com.erp.workorder.interfaces.rest.dto.request.AddItemsRequest;
import com.erp.workorder.interfaces.rest.dto.request.OpenWorkOrderRequest;
import com.erp.workorder.interfaces.rest.dto.request.TransitionRequest;
import com.erp.workorder.interfaces.rest.dto.request.WorkOrderItemRequest;
import com.erp.workorder.interfaces.rest.dto.response.WorkOrderDetailResponse;
import com.erp.workorder.interfaces.rest.dto.response.WorkOrderOpenedResponse;
import com.erp.workorder.interfaces.rest.dto.response.WorkOrderSummaryResponse;
import com.erp.workorder.interfaces.rest.dto.response.WorkOrderTransitionResponse;
import com.erp.workorder.interfaces.rest.support.RequestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The order API under {@code /api/v1/work-orders}: the workshop's list and
 * detail, the opening, the items, the machine's transitions and the saga's
 * doors (quote, approve, reject, cancel).
 *
 * <p>The roles per endpoint are declared HERE, in the contract. The
 * writing endpoints demand a role that can move the workshop's business;
 * reading is open to the mechanic too, who asks the board where the vehicle
 * stands without being able to move it.
 *
 * <p>The tenant of every operation is the VERIFIED token's — the bodies
 * carry no tenant field at all (spec P0 5.2).
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work orders", description = "The repair order and its lifecycle")
public class WorkOrderController {

    /**
     * The roles are declared VERBATIM on every endpoint: the role matrix
     * verification scans the annotations, and a constant reads as a roleless
     * endpoint — the tool can only see what the code says.
     */

    private final OpenWorkOrderUseCase openOrder;
    private final AddItemsToWorkOrderUseCase addItems;
    private final TransitionWorkOrderUseCase transitionOrder;
    private final QuoteWorkOrderUseCase quoteOrder;
    private final ApproveQuoteUseCase approveOrder;
    private final RejectQuoteUseCase rejectOrder;
    private final CancelWorkOrderUseCase cancelOrder;
    private final WorkOrderRepositoryPort repository;
    private final UnitOfWork unitOfWork;

    public WorkOrderController(OpenWorkOrderUseCase openOrder,
                               AddItemsToWorkOrderUseCase addItems,
                               TransitionWorkOrderUseCase transitionOrder,
                               QuoteWorkOrderUseCase quoteOrder,
                               ApproveQuoteUseCase approveOrder,
                               RejectQuoteUseCase rejectOrder,
                               CancelWorkOrderUseCase cancelOrder,
                               WorkOrderRepositoryPort repository,
                               UnitOfWork unitOfWork) {
        this.openOrder = openOrder;
        this.addItems = addItems;
        this.transitionOrder = transitionOrder;
        this.quoteOrder = quoteOrder;
        this.approveOrder = approveOrder;
        this.rejectOrder = rejectOrder;
        this.cancelOrder = cancelOrder;
        this.repository = repository;
        this.unitOfWork = unitOfWork;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Open a work order for a vehicle")
    public ResponseEntity<WorkOrderOpenedResponse> open(@Valid @RequestBody OpenWorkOrderRequest request) {
        OpenWorkOrderResult result = openOrder.open(new OpenWorkOrderCommand(
                RequestPrincipal.tenant(),
                request.customerId(),
                request.vehicleId(),
                request.branchId(),
                request.mechanicId(),
                request.diagnosis()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new WorkOrderOpenedResponse(
                result.orderId(), result.number(), result.status().name(), result.tenantId()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR', 'MECANICO')")
    @Operation(summary = "List the workshop's orders, optionally by status")
    public ResponseEntity<List<WorkOrderSummaryResponse>> list(
            @RequestParam(required = false) String status) {
        WorkOrderStatus filter = status == null ? null : WorkOrderStatus.valueOf(status);
        UUID tenant = RequestPrincipal.tenant();
        List<WorkOrderSummaryResponse> orders = unitOfWork.callInTransaction(() ->
                repository.listByTenant(tenant, filter).stream()
                        .map(WorkOrderController::toSummary)
                        .toList());
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR', 'MECANICO')")
    @Operation(summary = "One order's detail: items and history included")
    public ResponseEntity<WorkOrderDetailResponse> detail(@PathVariable UUID orderId) {
        UUID tenant = RequestPrincipal.tenant();
        Optional<WorkOrder> order = unitOfWork.callInTransaction(() ->
                repository.findById(tenant, orderId));
        return order.map(value -> ResponseEntity.ok(toDetail(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{orderId}/items")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Add the quoted parts to an order")
    public ResponseEntity<List<UUID>> addItem(@PathVariable UUID orderId,
                                              @Valid @RequestBody AddItemsRequest request) {
        List<WorkOrderItemCommand> items = request.items().stream()
                .map(item -> new WorkOrderItemCommand(item.sparePartId(), item.quantity(), item.laborPrice()))
                .toList();
        List<UUID> added = addItems.addItems(new AddItemsToWorkOrderCommand(
                RequestPrincipal.tenant(), orderId, items));
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    @PostMapping("/{orderId}/transition")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Move the order to the declared destination status")
    public ResponseEntity<WorkOrderTransitionResponse> transition(
            @PathVariable UUID orderId,
            @Valid @RequestBody TransitionRequest request) {
        WorkOrderStatus destination = WorkOrderStatus.valueOf(request.destination());
        TransitionWorkOrderResult result = transitionOrder.transition(new TransitionWorkOrderCommand(
                RequestPrincipal.tenant(), orderId, destination, RequestPrincipal.userId()));
        return ResponseEntity.ok(toTransition(result));
    }

    @PostMapping("/{orderId}/quote")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Quote the order: the saga reserves every item's stock")
    public ResponseEntity<WorkOrderTransitionResponse> quote(@PathVariable UUID orderId) {
        var result = quoteOrder.quote(new QuoteWorkOrderCommand(
                RequestPrincipal.tenant(), orderId, RequestPrincipal.userId()));
        return ResponseEntity.ok(toTransition(result));
    }

    @PostMapping("/{orderId}/approve")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Approve the quote: the saga consumes the reserved stock")
    public ResponseEntity<WorkOrderTransitionResponse> approve(@PathVariable UUID orderId) {
        var result = approveOrder.approve(new ApproveQuoteCommand(
                RequestPrincipal.tenant(), orderId, RequestPrincipal.userId()));
        return ResponseEntity.ok(toTransition(result));
    }

    @PostMapping("/{orderId}/reject")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Reject the quote: the saga releases every reservation")
    public ResponseEntity<WorkOrderTransitionResponse> reject(@PathVariable UUID orderId) {
        var result = rejectOrder.reject(new RejectQuoteCommand(
                RequestPrincipal.tenant(), orderId, RequestPrincipal.userId()));
        return ResponseEntity.ok(toTransition(result));
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_TALLER', 'ASESOR')")
    @Operation(summary = "Cancel the order: the saga releases the reservations it holds")
    public ResponseEntity<WorkOrderTransitionResponse> cancel(@PathVariable UUID orderId) {
        var result = cancelOrder.cancel(new CancelWorkOrderCommand(
                RequestPrincipal.tenant(), orderId, RequestPrincipal.userId()));
        return ResponseEntity.ok(toTransition(result));
    }

    // -- the aggregate's API views ---------------------------------------

    private static WorkOrderSummaryResponse toSummary(WorkOrder order) {
        return new WorkOrderSummaryResponse(
                order.id(),
                order.number().value(),
                order.status().name(),
                order.customer().id(),
                order.vehicle().id(),
                order.branch().id());
    }

    private static WorkOrderDetailResponse toDetail(WorkOrder order) {
        return new WorkOrderDetailResponse(
                order.id(),
                order.number().value(),
                order.status().name(),
                order.diagnosis(),
                order.customer().id(),
                order.vehicle().id(),
                order.branch().id(),
                order.mechanic().map(com.erp.workorder.domain.model.MechanicRef::id).orElse(null),
                order.openedAt(),
                order.closedAt().orElse(null),
                order.items().stream()
                        .map(item -> new WorkOrderDetailResponse.ItemResponse(
                                item.sparePart().id(), item.quantity(), item.laborPrice()))
                        .toList(),
                order.history().stream()
                        .map(entry -> new WorkOrderDetailResponse.HistoryEntryResponse(
                                entry.transition() == null ? null : entry.transition().origin().name(),
                                entry.transition() == null ? order.status().name()
                                        : entry.transition().destination().name(),
                                entry.changedBy(),
                                entry.changedAt()))
                        .toList());
    }

    private static WorkOrderTransitionResponse toTransition(ApproveQuoteResult result) {
        return new WorkOrderTransitionResponse(
                result.orderId(), result.status().name(), result.origin().name(), null);
    }

    private static WorkOrderTransitionResponse toTransition(QuoteWorkOrderResult result) {
        return new WorkOrderTransitionResponse(
                result.orderId(), result.status().name(), result.origin().name(), null);
    }

    private static WorkOrderTransitionResponse toTransition(RejectQuoteResult result) {
        return new WorkOrderTransitionResponse(
                result.orderId(), result.status().name(), result.origin().name(), null);
    }

    private static WorkOrderTransitionResponse toTransition(CancelWorkOrderResult result) {
        return new WorkOrderTransitionResponse(
                result.orderId(), result.status().name(), result.origin().name(), null);
    }

    private static WorkOrderTransitionResponse toTransition(TransitionWorkOrderResult result) {
        return new WorkOrderTransitionResponse(
                result.orderId(), result.status().name(), result.origin().name(), result.closedAt());
    }
}
