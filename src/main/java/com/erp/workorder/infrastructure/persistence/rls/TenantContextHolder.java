package com.erp.workorder.infrastructure.persistence.rls;

import java.util.UUID;

/**
 * The tenant context of the current request.
 *
 * <p>It is established ONLY from the authenticated principal (the verified
 * token's {@code tenant_id} claim, contrasted with {@code X-Tenant-ID} when
 * the header arrives), and it is cleared when the request ends. The body of
 * the request NEVER chooses the tenant: a caller claiming another tenant in
 * its payload is ignored (spec P0 5.2).
 *
 * <p>There is no mechanism anywhere to bypass the isolation.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
