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
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import java.util.Locale;
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

    private final MessageSource messageSource;

    public GlobalRestExceptionHandler() {
        this(null);
    }

    public GlobalRestExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String getMessage(String code, String defaultMessage, Object... args) {
        if (messageSource == null) {
            return defaultMessage;
        }
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args, defaultMessage, locale);
    }

    @ExceptionHandler(InvalidWorkOrderTransitionException.class)
    public ProblemDetail handleTransitionInvalid(InvalidWorkOrderTransitionException ex, WebRequest request) {
        String detail = getMessage("error.work-order.transition-invalid.detail",
                "The transition to the requested status is not declared by the machine");
        return problem("WORK_ORDER_TRANSITION_INVALID", HttpStatus.CONFLICT, detail, request);
    }

    /** The saga's doors are the ONLY path for the stock-touching moves. */
    @ExceptionHandler(SagaReservedTransitionException.class)
    public ProblemDetail handleSagaReserved(SagaReservedTransitionException ex, WebRequest request) {
        String detail = getMessage("error.work-order.saga-transition-reserved.detail",
                "The transition runs through its own saga door (quote, approve, reject, cancel)");
        return problem("WORK_ORDER_SAGA_TRANSITION_RESERVED", HttpStatus.CONFLICT, detail, request);
    }

    @ExceptionHandler(StockOperationRejectedException.class)
    public ProblemDetail handleStockReservationConflict(StockOperationRejectedException ex, WebRequest request) {
        // The inventory's stable code travels with the answer: the client
        // sees WHY it was rejected without a second call.
        String detail = getMessage("error.work-order.stock-reservation-conflict.detail",
                "The inventory rejected the saga operation");
        ProblemDetail problem = problem("STOCK_RESERVATION_CONFLICT", HttpStatus.CONFLICT, detail, request);
        if (ex.inventoryCode() != null && !ex.inventoryCode().isBlank()) {
            problem.setProperty("inventory_code", ex.inventoryCode());
        }
        return problem;
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    public ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex, WebRequest request) {
        String detail = getMessage("error.work-order.inventory-unavailable.detail",
                "The inventory service is unavailable, the operation was retried and failed");
        return problem("INVENTORY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, detail, request);
    }

    @ExceptionHandler(FleetUnavailableException.class)
    public ProblemDetail handleFleetUnavailable(FleetUnavailableException ex, WebRequest request) {
        String detail = getMessage("error.work-order.fleet-unavailable.detail",
                "The fleet service is unavailable, the order cannot be opened now");
        return problem("FLEET_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, detail, request);
    }

    @ExceptionHandler(VehicleUnknownByFleetException.class)
    public ProblemDetail handleVehicleUnknown(VehicleUnknownByFleetException ex, WebRequest request) {
        String detail = getMessage("error.work-order.vehicle-unknown.detail",
                "The fleet does not know the vehicle");
        return problem("WORK_ORDER_VEHICLE_UNKNOWN", HttpStatus.BAD_REQUEST, detail, request);
    }

    /** The vehicle belongs to another customer: the opening refuses, and logs. */
    @ExceptionHandler(VehicleOwnershipMismatchException.class)
    public ProblemDetail handleVehicleOwnershipMismatch(VehicleOwnershipMismatchException ex, WebRequest request) {
        String detail = getMessage("error.work-order.vehicle-ownership-mismatch.detail",
                "The vehicle is not registered to the order's customer");
        return problem("WORK_ORDER_VEHICLE_NOT_THE_CUSTOMERS", HttpStatus.FORBIDDEN, detail, request);
    }

    @ExceptionHandler(WorkOrderNotFoundException.class)
    public ProblemDetail handleNotFound(WorkOrderNotFoundException ex, WebRequest request) {
        String detail = getMessage("error.work-order.not-found.detail", "The order does not exist");
        return problem("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, detail, request);
    }

    /** The denial by role is an authorization decision, not an operation's failure: 403, never 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        String detail = getMessage("error.work-order.access-denied.detail",
                "The principal does not have the role the endpoint demands");
        return problem("WORK_ORDER_ACCESS_DENIED", HttpStatus.FORBIDDEN, detail, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, WebRequest request) {
        String detail = getMessage("error.work-order.unauthenticated.detail",
                "The request requires a verified access token");
        return problem("WORK_ORDER_UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, detail, request);
    }

    /** A route that does not exist is a 404, not a server error. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, WebRequest request) {
        String detail = getMessage("error.work-order.not-found.detail", "The requested resource does not exist");
        return problem("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, detail, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse(getMessage("error.work-order.validation-error.detail", "The request is not valid"));
        ProblemDetail problem = problem("WORK_ORDER_VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message, request);
        problem.setTitle(getMessage("error.work-order.validation-error.title", HttpStatus.BAD_REQUEST.getReasonPhrase()));
        return problem;
    }

    /** A body that is not even parseable is BAD REQUEST: the client erred, not the server. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformed(org.springframework.http.converter.HttpMessageNotReadableException ex,
                                         WebRequest request) {
        String detail = getMessage("error.work-order.malformed-request.detail", "The request body is not readable");
        return problem("WORK_ORDER_MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, detail, request);
    }

    /**
     * Every other exception: it is RECORDED WITH ITS TRACE and answered with
     * a generic error. The trace goes to the log, which is the operator's.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex, WebRequest request) {
        log.error("An unprovided error in {} {}", request.getDescription(false), ex.getMessage(), ex);
        String detail = getMessage("error.work-order.internal-error.detail", "An internal error occurred");
        return problem("WORK_ORDER_INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, detail, request);
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
