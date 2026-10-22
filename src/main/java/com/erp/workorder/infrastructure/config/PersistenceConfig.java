package com.erp.workorder.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The transactional demarcation configuration.
 *
 * <p>The order of the advice matters: Spring's transaction interceptor and
 * the aspect establishing the tenant context compete for the same join
 * point. Without fixing the order, the order is indeterminate and the
 * {@code SET LOCAL} can run OUTSIDE the transaction, where it has no
 * effect: the query would then execute without the isolation filter.
 *
 * <p>With the transaction interceptor at order 0 and the aspect at order 1,
 * the aspect ends up INSIDE the transaction, which is where
 * {@code SET LOCAL} applies.
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class PersistenceConfig {

    /**
     * The order of the Row-Level Security aspect. In Spring, a LOWER order
     * value means higher precedence, hence an OUTER advice. The aspect must
     * be INNER to the transaction interceptor, so its order is higher than
     * this one's.
     */
    public static final int TENANT_RLS_ASPECT_ORDER = 1;
}
