package com.erp.workorder.interfaces.rest.support;

import com.erp.workorder.infrastructure.persistence.rls.TenantContextHolder;

import java.util.UUID;

/**
 * The tenant context scope of one operation.
 *
 * <p>The context is established from the authenticated principal and
 * RESTORED when the operation ends, so it never leaks to the next request
 * reusing the same thread. Use it as try-with-resources: the close restores
 * whatever the thread carried before, which is usually nothing.
 */
public final class TenantContextScope implements AutoCloseable {

    private final UUID previousTenantId;
    private boolean closed;

    private TenantContextScope(UUID previousTenantId) {
        this.previousTenantId = previousTenantId;
    }

    public static TenantContextScope forTenant(UUID tenantId) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        return new TenantContextScope(previous);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previousTenantId == null) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.setTenantId(previousTenantId);
        }
    }
}
