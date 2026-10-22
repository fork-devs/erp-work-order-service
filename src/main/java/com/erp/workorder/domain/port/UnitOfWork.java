package com.erp.workorder.domain.port;

/**
 * The transactional boundary of a business operation.
 *
 * <p>It is a domain abstraction, free of framework dependencies, so the
 * application layer can demand that an operation writing several
 * repositories commits as ONE, regardless of how the transaction is
 * implemented.
 *
 * <p>Without it, every repository call opens its own transaction: two
 * consecutive writes are two separate transactions, and an operation that
 * needs to be atomic (the status change, its history row and the outbox
 * event leaving together) cannot be.
 */
public interface UnitOfWork {

    void runInTransaction(Runnable work);

    <T> T callInTransaction(java.util.function.Supplier<T> work);
}
