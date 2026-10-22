package com.erp.workorder.application.service;

import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.exception.VehicleOwnershipMismatchException;
import com.erp.workorder.application.exception.VehicleUnknownByFleetException;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The reserved numbering of the opening, with REAL concurrency: the
 * counter is claimed and advanced inside the transaction, so two
 * simultaneous openings of the same tenant take DIFFERENT consecutive
 * numbers, and two different tenants CAN take the same number — the
 * counter is per tenant.
 */
class OpenWorkOrderUseCaseTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private FakeFleetPort fleet;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @BeforeEach
    void clean() throws SQLException {
        fleet.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
        }
    }

    @AfterEach
    void stopTheThreads() {
        executor.shutdownNow();
    }

    private OpenWorkOrderCommand command(UUID tenantId, UUID customerId, UUID vehicleId) {
        return new OpenWorkOrderCommand(
                tenantId, customerId, vehicleId, UUID.randomUUID(), UUID.randomUUID(), null);
    }

    /**
     * Opens the order as a REQUEST would: the tenant context of the
     * authenticated principal surrounds the use case, per thread.
     */
    private <T> T asRequest(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    @Test
    void simultaneousOpeningsOfTheSameTenantTakeConsecutiveNumbers() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, customer);

        List<Future<OpenWorkOrderResult>> openings = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Callable<OpenWorkOrderResult> opening =
                    () -> asRequest(tenant, () -> openOrder.open(command(tenant, customer, vehicle)));
            openings.add(executor.submit(opening));
        }

        List<String> numbers = new ArrayList<>();
        for (Future<OpenWorkOrderResult> opening : openings) {
            numbers.add(opening.get().number());
        }

        assertEquals(8, numbers.size(), "every simultaneous opening succeeded");
        assertEquals(8, numbers.stream().distinct().count(), "no two orders carry the same number");
        // In what ORDER the threads took the numbers is up to who wins the
        // row lock; WHAT the set holds is the counter's invariant: the eight
        // consecutive numbers, none reused.
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(numbers);
        int expectedSequence = 1;
        for (String number : sorted) {
            assertEquals(String.format("WO-%06d", expectedSequence++), number,
                    "the counter only advances consecutively, bounded");
        }
    }

    @Test
    void twoDifferentTenantsCanTakeTheSameNumber() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenantA, vehicle, customer);
        fleet.registerVehicle(tenantB, vehicle, customer);

        List<Future<String>> numbersA = new ArrayList<>();
        List<Future<String>> numbersB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            numbersA.add(executor.submit(() ->
                    asRequest(tenantA, () -> openOrder.open(command(tenantA, customer, vehicle)).number())));
            numbersB.add(executor.submit(() ->
                    asRequest(tenantB, () -> openOrder.open(command(tenantB, customer, vehicle)).number())));
        }

        java.util.TreeSet<String> numbersOfA = new java.util.TreeSet<>();
        java.util.TreeSet<String> numbersOfB = new java.util.TreeSet<>();
        for (Future<String> number : numbersA) {
            numbersOfA.add(number.get());
        }
        for (Future<String> number : numbersB) {
            numbersOfB.add(number.get());
        }

        assertEquals(java.util.Set.of("WO-000001", "WO-000002", "WO-000003"), numbersOfA,
                "each tenant's thread takes consecutive numbers from ITS OWN counter");
        assertEquals(java.util.Set.of("WO-000001", "WO-000002", "WO-000003"), numbersOfB,
                "the counter is per tenant, not global: B can carry A's same numbers");
    }

    @Test
    void aVehicleRegisteredToAnotherCustomerIsRejectedWithoutOrder() {
        UUID tenant = UUID.randomUUID();
        UUID realOwner = UUID.randomUUID();
        UUID claimedCustomer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(tenant, vehicle, realOwner);

        assertThrows(VehicleOwnershipMismatchException.class,
                () -> openOrder.open(command(tenant, claimedCustomer, vehicle)),
                "fleet decides the ownership, not the payload");
    }

    @Test
    void aVehicleFleetDoesNotKnowIsRejected() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID unknownVehicle = UUID.randomUUID();

        assertThrows(VehicleUnknownByFleetException.class,
                () -> openOrder.open(command(tenant, customer, unknownVehicle)),
                "fail-closed: without fleet verification there is no order");
    }

    @Test
    void aRejectedOpeningLeavesNoOrderRowBehind() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();

        assertThrows(VehicleUnknownByFleetException.class,
                () -> openOrder.open(command(tenant, UUID.randomUUID(), vehicle)));

        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM work_orders WHERE tenant_id = '" + tenant + "'")) {
            rows.next();
            assertEquals(0, rows.getInt(1), "the rejected opening must not leave a half-open order");
        }
    }
}
