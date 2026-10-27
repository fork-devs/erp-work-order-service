package com.erp.workorder.infrastructure.security;

import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import com.erp.workorder.support.TestJwksServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token must be verifiable, current, and declare a tenant; the tenant is
 * decided by the token only. Every family of unusable token ends in 401 —
 * and the request never reaches the chain — a header that contradicts the
 * token ends in 403, and a verified token passes: it establishes the tenant,
 * the principal, the roles AND delivers its bearer string to the saga
 * clients.
 */
class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(new JwksKeyManager(TestJwksServer.jwksUrl(), List.of("dev")));

    @AfterEach
    void clearTheRequestState() {
        TenantContextHolder.clear();
        PrincipalTokenHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/work-orders");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    @Test
    void aTokenTheIssuerCannotVerifyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(requestWith("not-even-a-token"), response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.reached.get(), "a refused token never reaches the service");
        assertNull(TenantContextHolder.getTenantId());
        assertNull(PrincipalTokenHolder.getToken());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = TestJwksServer.signExpiredToken(UUID.randomUUID(), List.of("ASESOR"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(requestWith(token), response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.reached.get());
        assertNull(TenantContextHolder.getTenantId(), "an expired token establishes no tenant");
    }

    @Test
    void aTokenWithoutTenantDealsNoWorkshop() throws Exception {
        String token = TestJwksServer.signTokenWithoutTenant(List.of("ASESOR"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(requestWith(token), response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.reached.get());
        assertNull(PrincipalTokenHolder.getToken(), "a rejected token leaves nothing behind");
    }

    @Test
    void aHeaderThatDisagreesWithTheTokenIsForbidden() throws Exception {
        UUID tenant = UUID.randomUUID();
        String token = TestJwksServer.signToken(tenant, List.of("ASESOR"));
        MockHttpServletRequest request = requestWith(token);
        request.addHeader("X-Tenant-ID", UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertFalse(chain.reached.get());
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void aVerifiedTokenPassesAndEstablishesTheWholeContext() throws Exception {
        UUID tenant = UUID.randomUUID();
        String token = TestJwksServer.signToken(tenant, List.of("ASESOR", "MECANICO"));
        MockHttpServletRequest request = requestWith(token);
        request.addHeader("X-Tenant-ID", tenant);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // The filter's context lives for the DURATION of the request: it is
        // captured inside the chain, where the service would read it.
        CapturingChain chain = new CapturingChain();
        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.reached.get(), "the verified token goes through");
        assertEquals(tenant, chain.tenantId.get(), "the caller's tenant comes from the token");
        assertEquals(token, chain.bearerToken.get(), "the saga clients re-send the SAME bearer");
        assertEquals(2, chain.authorityCount.get(), "the token granted exactly its declared roles");
        assertTrue(chain.allAuthoritiesAreRoles.get());
        // When the request ends, nothing survives unmanaged.
        assertNull(TenantContextHolder.getTenantId());
        assertNull(PrincipalTokenHolder.getToken());
    }

    @Test
    void aRequestWithoutTokenProceedsRefusedLaterOn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        // The filter does not answer by itself: the chain (SecurityConfig)
        // refuses the anonymous principal later on the path.
        filter.doFilter(requestWith(null), response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.reached.get());
        assertNull(TenantContextHolder.getTenantId());
    }

    /** Captures what the service would see INSIDE the request's lifetime. */
    private static class CapturingChain implements FilterChain {
        final AtomicBoolean reached = new AtomicBoolean(false);
        final AtomicReference<UUID> tenantId = new AtomicReference<>();
        final AtomicReference<String> bearerToken = new AtomicReference<>();
        final AtomicInteger authorityCount = new AtomicInteger();
        final AtomicBoolean allAuthoritiesAreRoles = new AtomicBoolean(true);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            reached.set(true);
            tenantId.set(TenantContextHolder.getTenantId());
            bearerToken.set(PrincipalTokenHolder.getToken());
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                authorityCount.set(authentication.getAuthorities().size());
                allAuthoritiesAreRoles.set(authentication.getAuthorities().stream()
                        .allMatch(authority -> authority.getAuthority().startsWith("ROLE_")));
            }
        }
    }

    /** Marks whether the request reached the chain behind the filter. */
    private static class RecordingChain implements FilterChain {
        final AtomicBoolean reached = new AtomicBoolean(false);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            reached.set(true);
        }
    }
}
