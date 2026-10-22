package com.erp.workorder.interfaces.rest.support;

import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The context does not survive the request: the scope restores whatever the
 * thread carried before, and threads are reused between requests in a
 * servlet container, so a leaked tenant would be the NEXT request's tenant —
 * a cross-tenant read by accident. The holder only accepts values from the
 * authenticated principal; the scope is who guarantees the restoration.
 */
class TenantContextScopeTest {

    @BeforeEach
    void leaveNoTenantFromAnotherTest() {
        TenantContextHolder.clear();
    }

    @Test
    void theContextIsEstablishedInsideAndGoneAfterTheScope() {
        UUID tenant = UUID.randomUUID();

        try (TenantContextScope scope = TenantContextScope.forTenant(tenant)) {
            assertEquals(tenant, TenantContextHolder.getTenantId());
        }

        assertNull(TenantContextHolder.getTenantId(), "the context must not survive the request");
    }

    @Test
    void aNestedScopeRestoresTheOuterTenant() {
        UUID outerTenant = UUID.randomUUID();
        UUID innerTenant = UUID.randomUUID();

        try (TenantContextScope outer = TenantContextScope.forTenant(outerTenant)) {
            try (TenantContextScope inner = TenantContextScope.forTenant(innerTenant)) {
                assertEquals(innerTenant, TenantContextHolder.getTenantId());
            }
            assertEquals(outerTenant, TenantContextHolder.getTenantId(), "the outer tenant survives the inner scope");
        }

        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void closingARestoredValueBringsThePreviousTenantBack() {
        UUID previousTenant = UUID.randomUUID();
        UUID operationTenant = UUID.randomUUID();

        TenantContextHolder.setTenantId(previousTenant);
        try (TenantContextScope ignored = TenantContextScope.forTenant(operationTenant)) {
            assertEquals(operationTenant, TenantContextHolder.getTenantId());
        }

        assertEquals(previousTenant, TenantContextHolder.getTenantId(),
                "the thread's previous tenant is restored, not wiped");

        TenantContextHolder.clear();
    }

    @Test
    void closingTwiceIsHarmless() {
        TenantContextScope scope = TenantContextScope.forTenant(UUID.randomUUID());
        scope.close();
        scope.close();

        assertNull(TenantContextHolder.getTenantId());
    }
}
