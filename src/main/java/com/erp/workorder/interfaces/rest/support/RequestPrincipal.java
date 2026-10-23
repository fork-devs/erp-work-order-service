package com.erp.workorder.interfaces.rest.support;

import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The caller derived from the request's context: the tenant from the
 * verified token (the body NEVER chooses it) and the acting user from the
 * token's subject.
 */
public final class RequestPrincipal {

    private RequestPrincipal() {
    }

    /** The tenant the verified token declared. */
    public static UUID tenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "The tenant context is not established: a request reached the API "
                    + "without passing the authentication filter.");
        }
        return tenantId;
    }

    /** The acting user's identifier (the token's subject). */
    public static UUID userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    "The request has no usable principal: the API cannot attribute the action.");
        }
    }
}
