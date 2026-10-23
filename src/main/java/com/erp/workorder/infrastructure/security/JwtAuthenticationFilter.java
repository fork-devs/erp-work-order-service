package com.erp.workorder.infrastructure.security;

import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Validates the access token the edge forwarded and establishes the tenant
 * context.
 *
 * <p>The tenant is derived EXCLUSIVELY from the {@code tenant_id} claim of a
 * verified token. The {@code X-Tenant-ID} header is a hint: when it arrives,
 * it is checked against the token's tenant and the request is rejected when
 * they do not agree.
 *
 * <p>A token that is not verifiable, expired, or that declares no tenant is
 * rejected: the service does not operate without a verified principal.
 *
 * <p>The verified token's BEARER STRING is also placed in
 * {@link PrincipalTokenHolder}: the saga clients (inventory, fleet) re-send
 * it, so the whole path carries ONE principal — no service credentials, no
 * anonymous internal call trusted because of the network. The holder is
 * cleared when the request ends, together with the security context.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwksKeyManager jwksKeyManager;

    public JwtAuthenticationFilter(JwksKeyManager jwksKeyManager) {
        this.jwksKeyManager = jwksKeyManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        String tenantHeader = request.getHeader("X-Tenant-ID");
        String traceId = traceOf(request);

        // The trace and the tenant go to the MDC: every log line of the
        // request says whose it is and which trace it belongs to.
        org.slf4j.MDC.put("trace_id", traceId);
        org.slf4j.MDC.put("tenant_id", "-");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                SignedJWT signedJWT = SignedJWT.parse(token);
                RSAPublicKey publicKey = jwksKeyManager.getPublicKey(signedJWT.getHeader().getKeyID());

                if (publicKey == null) {
                    log.warn("No key is published for the token's key identifier");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token not verifiable");
                    return;
                }

                if (!signedJWT.verify(new RSASSAVerifier(publicKey))) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token signature");
                    return;
                }

                JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
                Date expiration = claims.getExpirationTime();
                if (expiration == null || !expiration.after(new Date())) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token expired");
                    return;
                }

                Object tenantClaim = claims.getClaim("tenant_id");
                if (tenantClaim == null) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "The token declares no tenant");
                    return;
                }

                UUID tenantId;
                try {
                    tenantId = UUID.fromString(tenantClaim.toString());
                } catch (IllegalArgumentException e) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "The token's tenant is not valid");
                    return;
                }

                if (tenantHeader != null && !tenantHeader.trim().isEmpty()) {
                    UUID requested;
                    try {
                        requested = UUID.fromString(tenantHeader.trim());
                    } catch (IllegalArgumentException e) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                                "X-Tenant-ID must be a valid UUID");
                        return;
                    }
                    if (!requested.equals(tenantId)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                "The requested tenant does not match the token's tenant");
                        return;
                    }
                }

                TenantContextHolder.setTenantId(tenantId);
                PrincipalTokenHolder.setToken(token);
                org.slf4j.MDC.put("tenant_id", tenantId.toString());

                List<?> rolesList = claims.getClaim("roles") instanceof List<?> list ? list : List.of();
                List<SimpleGrantedAuthority> authorities = rolesList.stream()
                        .map(Object::toString)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception e) {
                log.warn("The token is not processable: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            PrincipalTokenHolder.clear();
            SecurityContextHolder.clearContext();
            org.slf4j.MDC.remove("tenant_id");
            org.slf4j.MDC.remove("trace_id");
        }
    }

    /**
     * The request's trace identifier.
     *
     * <p>The edge propagates the trace; when none arrives, one is generated,
     * so every request carries a trace it can be followed with.
     */
    private String traceOf(HttpServletRequest request) {
        String trace = request.getHeader("X-Trace-ID");
        if (trace == null || trace.isBlank()) {
            trace = request.getHeader("traceparent");
        }
        return trace != null && !trace.isBlank() ? trace.trim() : UUID.randomUUID().toString();
    }
}
