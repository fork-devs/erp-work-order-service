package com.erp.workorder.infrastructure.persistence.adapter;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A persistence probe ONLY FOR TESTS: it lives in the adapter package so the
 * Row-Level Security aspect applies to it, and it executes against the
 * transaction-bound connection exactly the way a real adapter does. With it,
 * the isolation rules can be exercised before any business adapter exists.
 */
@Component
public class RlsIsolationProbeAdapter {

    private final EntityManager entityManager;

    @Autowired
    public RlsIsolationProbeAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Counts the work order rows the policy lets this context see. */
    public long countWorkOrders() {
        Object result = entityManager.createNativeQuery("SELECT count(*) FROM work_orders").getSingleResult();
        return ((Number) result).longValue();
    }
}
