package com.erp.workorder.infrastructure.security;

import com.erp.workorder.application.logging.SecurityEventLogger;
import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answer to a request whose principal lacks the endpoint's declared
 * role: <strong>403 Forbidden</strong>.
 *
 * <p>The denial by role is an authorization decision and is kept apart from
 * an operation's failure: the client may retry with another role, but not
 * with the same request.
 */
@Component
public class ForbiddenAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws java.io.IOException {
        // The denied access is a SECURITY EVENT: it is recorded with the
        // same schema as the rest, so it can be queried like the others.
        SecurityEventLogger.registrar(
                "acceso_denegado",
                TenantContextHolder.getTenantId(),
                null,
                MDC.get("trace_id"),
                "denegado",
                Map.of("ruta", request.getRequestURI(), "metodo", request.getMethod()));

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://erp-automotive.pe/errors/security");
        body.put("title", "Forbidden");
        body.put("status", HttpServletResponse.SC_FORBIDDEN);
        body.put("detail", "The principal does not have the role the endpoint demands");
        body.put("timestamp", Instant.now().toString());

        response.getWriter().write(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
    }
}
