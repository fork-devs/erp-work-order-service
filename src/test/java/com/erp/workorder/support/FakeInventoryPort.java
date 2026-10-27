package com.erp.workorder.support;

import com.erp.workorder.application.exception.StockOperationRejectedException;
import com.erp.workorder.domain.port.InventoryPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fake inventory registered IN THE TEST CONTEXT, with the claim
 * semantics the real one has: an idempotency key can only be claimed
 * ONCE, and any repeat of the key returns the FIRST call's confirmation.
 * The {@code @Primary} mark lives in test support, which is not on the
 * classpath of the packaged service, so production resolves the real
 * {@code InventoryClient}.
 *
 * <p>The fake records every reserve request so the tests can assert the
 * keys, branches and quantities that travelled, and it can be forced to
 * reject the NEXT operation (business rejection) or to fail transiently
 * (availability), exactly like the real inventory's failure families.
 */
@Component
@Primary
public class FakeInventoryPort implements InventoryPort {

    /** The movements that were actually claimed: key -> registration. */
    private final Map<String, StockConfirmation> claimed = new ConcurrentHashMap<>();

    /** The last requests that reached the fake, in arrival order. */
    private final List<StockReservation> reserveRequests = new CopyOnWriteArrayList<>();

    /** When set, the NEXT operation rejects with this exception (once). */
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

    public static final String RESERVE = "RESERVE";
    public static final String COMMIT = "COMMIT";
    public static final String RELEASE = "RELEASE";

    @Override
    public StockConfirmation reserve(StockReservation reservation) {
        reserveRequests.add(reservation);
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        return claimed.computeIfAbsent("reserve:" + reservation.idempotencyKey(),
                key -> register(reservation, RESERVE));
    }

    @Override
    public StockConfirmation commit(StockReservation reservation) {
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        return claimed.computeIfAbsent("commit:" + reservation.idempotencyKey(),
                key -> new StockConfirmation(UUID.randomUUID(), COMMIT, reservation.quantity()));
    }

    @Override
    public StockConfirmation release(StockReservation reservation) {
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        return claimed.computeIfAbsent("release:" + reservation.idempotencyKey(),
                key -> new StockConfirmation(UUID.randomUUID(), RELEASE, reservation.quantity()));
    }

    private StockConfirmation register(StockReservation reservation, String type) {
        return new StockConfirmation(UUID.randomUUID(), type, reservation.quantity());
    }

    public List<StockReservation> reserveRequests() {
        return List.copyOf(reserveRequests);
    }

    public List<Map.Entry<String, StockConfirmation>> claimedMovements() {
        return List.copyOf(claimed.entrySet());
    }

    public Optional<StockConfirmation> claimed(String operation, String idempotencyKey) {
        return Optional.ofNullable(claimed.get(operation + ":" + idempotencyKey));
    }

    /** The next operation of ANY kind rejects like a business answer (once). */
    public void rejectNext(StockOperationRejectedException rejection) {
        nextFailure.set(rejection);
    }

    public void reset() {
        claimed.clear();
        reserveRequests.clear();
        nextFailure.set(null);
    }
}
