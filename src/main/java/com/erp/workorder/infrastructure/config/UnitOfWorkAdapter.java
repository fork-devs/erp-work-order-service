package com.erp.workorder.infrastructure.config;

import com.erp.workorder.domain.port.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * The transactional boundary implementation over {@link TransactionTemplate}.
 *
 * <p>The template uses the DEFAULT propagation: when a transaction is
 * already open, the operation joins it instead of opening another one. That
 * is what allows nesting demarcations without creating independent
 * transactions, and it is the reason the persistence adapters must never
 * declare {@code @Transactional} by themselves: they join the transaction
 * the application layer opened through this port.
 */
@Component
public class UnitOfWorkAdapter implements UnitOfWork {

    private final TransactionTemplate transactionTemplate;

    public UnitOfWorkAdapter(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void runInTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    @Override
    public <T> T callInTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
