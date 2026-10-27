package com.erp.workorder.interfaces.rest.advice;

import com.erp.workorder.application.exception.FleetUnavailableException;
import com.erp.workorder.application.exception.InventoryUnavailableException;
import com.erp.workorder.application.exception.SagaReservedTransitionException;
import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.application.exception.VehicleOwnershipMismatchException;
import com.erp.workorder.application.exception.VehicleUnknownByFleetException;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.domain.model.InvalidWorkOrderTransitionException;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.UUID;

/**
 * The API's global error handling.
 *
 * <p>The error body is a {@code ProblemDetail} (RFC 9457) with a
 * <strong>stable code per family</strong>: the message can be rewritten,
 * the code does not change without an incompatible change of contract. The
 * error also carries the <strong>tenant</strong>, so the frontend can show
 * it without guessing.
 *
 * <p>The internal error exposes neither the exception's class nor its
 * message: the diagnosis goes to the log, which belongs to the operator.
 */
@Hidden
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

    private static final String BASE_TYPE = "https://erp-automotive.pe/errors/";

    @ExceptionHandler(InvalidWorkOrderTransitionException.class)
    public ProblemDetail handleTransitionInvalid(InvalidWorkOrderTransitionException ex, WebRequest request) {
        return problem("WORK_ORDER_TRANSITION_INVALID", HttpStatus.CONFLICT,
                "The transition to the requested status is not declared by the machine", request);
    }

    /** The saga's doors are the ONLY path for the stock-touching moves. */
    @ExceptionHandler(SagaReservedTransitionException.class)
    public ProblemDetail handleSagaReserved(SagaReservedTransitionException ex, WebRequest request) {
        return problem("WORK_ORDER_SAGA_TRANSITION_RESERVED", HttpStatus.CONFLICT,
                "The transition runs through its own saga door (quote, approve, reject, cancel)", request);
    }

    @ExceptionHandler(StockOperationRejectedException.class)
    public ProblemDetail handleStockReservationConflict(StockOperationRejectedException ex, WebRequest request) {
        // The inventory's stable code travels with the answer: the client
        // sees WHY it was rejected without a second call.
        ProblemDetail problem = problem("STOCK_RESERVATION_CONFLICT", HttpStatus.CONFLICT,
                "The inventory rejected the saga operation", request);
        if (ex.inventoryCode() != null && !ex.inventoryCode().isBlank()) {
            problem.setProperty("inventory_code", ex.inventoryCode());
        }
        return problem;
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    public ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex, WebRequest request) {
        return problem("INVENTORY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "The inventory service is unavailable, the operation was retried and failed", request);
    }

    @ExceptionHandler(FleetUnavailableException.class)
    public ProblemDetail handleFleetUnavailable(FleetUnavailableException ex, WebRequest request) {
        return problem("FLEET_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "The fleet service is unavailable, the order cannot be opened now", request);
    }

    @ExceptionHandler(VehicleUnknownByFleetException.class)
    public ProblemDetail handleVehicleUnknown(VehicleUnknownByFleetException ex, WebRequest request) {
        return problem("WORK_ORDER_VEHICLE_UNKNOWN", HttpStatus.BAD_REQUEST,
                "The fleet does not know the vehicle", request);
    }

    /** The vehicle belongs to another customer: the opening refuses, and logs. */
    @ExceptionHandler(VehicleOwnershipMismatchException.class)
    public ProblemDetail handleVehicleOwnershipMismatch(VehicleOwnershipMismatchException ex, WebRequest request) {
        return problem("WORK_ORDER_VEHICLE_NOT_THE_CUSTOMERS", HttpStatus.FORBIDDEN,
                "The vehicle is not registered to the order's customer", request);
    }

    @ExceptionHandler(WorkOrderNotFoundException.class)
    public ProblemDetail handleNotFound(WorkOrderNotFoundException ex, WebRequest request) {
        return problem("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND,
                "The order does not exist", request);
    }

    /** The denial by role is an authorization decision, not an operation's failure: 403, never 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem("WORK_ORDER_ACCESS_DENIED", HttpStatus.FORBIDDEN,
                "The principal does not have the role the endpoint demands", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, WebRequest request) {
        return problem("WORK_ORDER_UNAUTHENTICATED", HttpStatus.UNAUTHORIZED,
                "The request requires a verified access token", request);
    }

    /** A route that does not exist is a 404, not a server error. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, WebRequest request) {
        return problem("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND,
                "The requested resource does not exist", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("The request is not valid");
        return problem("WORK_ORDER_VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message, request);
    }

    /** A body that is not even parseable is BAD REQUEST: the client erred, not the server. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformed(org.springframework.http.converter.HttpMessageNotReadableException ex,
                                         WebRequest request) {
        return problem("WORK_ORDER_MALFORMED_REQUEST", HttpStatus.BAD_REQUEST,
                "The request body is not readable", request);
    }

    /**
     * Every other exception: it is RECORDED WITH ITS TRACE and answered with
     * a generic error. The trace goes to the log, which is the operator's.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex, WebRequest request) {
        log.error("An unprovided error in {} {}", request.getDescription(false), ex.getMessage(), ex);
        return problem("WORK_ORDER_INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred", request);
    }

    private ProblemDetail problem(String code, HttpStatus status, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(BASE_TYPE + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);

        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null) {
            problem.setProperty("tenant_id", tenantId.toString());
        }
        return problem;
    }
}
