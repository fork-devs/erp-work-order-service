package com.erp.workorder.infrastructure.persistence.rls;

import com.erp.workorder.infrastructure.config.PersistenceConfig;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Establishes the tenant context on the database session so the
 * Row-Level Security policy can filter the rows.
 *
 * <p>The isolation fails CLOSED: with no active transaction the
 * {@code SET LOCAL} would have no effect and the query would run WITHOUT a
 * filter, so the operation is rejected instead of executing unisolated.
 * Equally, with no tenant in the holder the operation is rejected instead
 * of relying on the policy to filter nothing.
 *
 * <p>The context is always the one of the AUTHENTICATED PRINCIPAL, never the
 * row being operated on: if the row set it, the policy check would always
 * succeed and the isolation would isolate nothing.
 */
@Aspect
@Component
@Order(PersistenceConfig.TENANT_RLS_ASPECT_ORDER)
public class PostgresTenantRlsAspect {

    private final EntityManager entityManager;

    @Autowired
    public PostgresTenantRlsAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Every persistence operation must run inside a transaction: outside it
     * the tenant context cannot be established and the isolation policy
     * would not apply.
     */
    @Before("execution(* com.erp.workorder.infrastructure.persistence.adapter..*(..))")
    public void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new TenantContextMissingException(
                    "The persistence operation must run inside a transaction: "
                    + "outside it the tenant isolation cannot be guaranteed.");
        }
    }

    /**
     * Every persistence operation requires the tenant context established
     * from the authenticated principal.
     *
     * <p>The value is interpolated into the statement because
     * {@code SET LOCAL} does not accept prepared parameters. The value is an
     * already validated UUID, so it introduces no injection path.
     */
    @Before("execution(* com.erp.workorder.infrastructure.persistence.adapter..*(..))")
    public void applyTenantContext() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new TenantContextMissingException(
                    "The operation requires a tenant context established and it has none.");
        }
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '" + tenantId + "'").executeUpdate();
    }
}
