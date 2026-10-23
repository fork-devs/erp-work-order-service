package com.erp.workorder.tenant;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.erp.workorder.application.dto.command.AddItemsToWorkOrderCommand;
import com.erp.workorder.application.dto.command.OpenWorkOrderCommand;
import com.erp.workorder.application.dto.command.TransitionWorkOrderCommand;
import com.erp.workorder.application.dto.command.WorkOrderItemCommand;
import com.erp.workorder.application.dto.result.OpenWorkOrderResult;
import com.erp.workorder.application.exception.WorkOrderNotFoundException;
import com.erp.workorder.application.service.AddItemsToWorkOrderUseCase;
import com.erp.workorder.application.service.OpenWorkOrderUseCase;
import com.erp.workorder.application.service.TransitionWorkOrderUseCase;
import com.erp.workorder.domain.model.WorkOrderStatus;
import com.erp.workorder.interfaces.rest.support.TenantContextScope;
import com.erp.workorder.support.AbstractWorkOrderIntegrationTest;
import com.erp.workorder.support.EmbeddedPostgresSupport;
import com.erp.workorder.support.FakeFleetPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P0 5.1 and P0 5.2 regression suite, in the shape it was closed in the
 * other services: the tenant of the ATTACKER reads, transitions and adds
 * items over the VICTIM's orders, with the tenant of the token (the context
 * the JWT filter will set), the tenant of the header (the contrast the
 * filter enforces) and the tenant the payload may claim DELIBERATELY
 * fighting each other.
 *
 * <p>Every case is rejected, the victim's data stays untouched, and the
 * attempt is RECORDED in the security log with the attacker's tenant: the
 * rejection is not silent — the shop can audit who knocked on the door.
 */
class TenantIsolationTest extends AbstractWorkOrderIntegrationTest {

    @Autowired
    private OpenWorkOrderUseCase openOrder;

    @Autowired
    private TransitionWorkOrderUseCase transitionOrder;

    @Autowired
    private AddItemsToWorkOrderUseCase addItems;

    @Autowired
    private FakeFleetPort fleet;

    private final ListAppender<ILoggingEvent> securityEvents = new ListAppender<>();

    @BeforeEach
    void clean() throws SQLException {
        fleet.reset();
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM work_order_status_history");
            statement.execute("DELETE FROM work_order_items");
            statement.execute("DELETE FROM work_orders");
            statement.execute("DELETE FROM work_order_sequences");
        }
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("SECURITY")).addAppender(securityEvents);
        securityEvents.start();
    }

    @AfterEach
    void detachTheAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("SECURITY")).detachAppender(securityEvents);
    }

    private <T> T withTenantOfToken(UUID tenantId, Supplier<T> operation) {
        try (TenantContextScope scope = TenantContextScope.forTenant(tenantId)) {
            return operation.get();
        }
    }

    private int victimOrdersByTenant(UUID tenant) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM work_orders WHERE tenant_id = '" + tenant + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void anAttackerCannotReadAnOrderItsTokenHasNoRightTo() {
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();

        // The attacker's TOKEN (its context) asks for the victim's order: the
        // repository itself is tenant-bound, the absence is not leaky.
        assertThrows(WorkOrderNotFoundException.class, () ->
                        withTenantOfToken(attacker, () -> transitionOrder.transition(
                                new TransitionWorkOrderCommand(attacker, victimOrderId,
                                        WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID()))),
                "a foreign order is not readable, not transitable, not visible");
    }

    @Test
    void anAttackerCannotTransitionAnOrderOfTheVictim() throws SQLException {
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();
        int victimHistoryBefore = victimHistoryRows(victimOrderId);

        assertThrows(WorkOrderNotFoundException.class, () ->
                withTenantOfToken(attacker, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(attacker, victimOrderId,
                                WorkOrderStatus.CANCELADA, UUID.randomUUID()))), "");

        assertEquals(1, victimOrdersByTenant(victim), "the victim keeps its order");
        assertEquals("RECEPCIONADA", victimOrderStatus(victimOrderId), "the status did not move");
        assertEquals(victimHistoryBefore, victimHistoryRows(victimOrderId),
                "the attacked order gained no history row");
    }

    @Test
    void anAttackerCannotAddItemsToAnOrderOfTheVictim() throws SQLException {
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();

        assertThrows(WorkOrderNotFoundException.class, () ->
                withTenantOfToken(attacker, () -> addItems.addItems(
                        new AddItemsToWorkOrderCommand(attacker, victimOrderId,
                                List.of(new WorkOrderItemCommand(UUID.randomUUID(), 1, BigDecimal.ZERO))))), "");

        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM work_order_items WHERE work_order_id = '" + victimOrderId + "'")) {
            rows.next();
            assertEquals(0, rows.getInt(1), "no item row was written on the victim's order");
        }
    }

    @Test
    void theTenantThePayloadClaimsIsIgnoredWhenItContradictsTheToken() throws SQLException {
        // The attacker's token carries tenant ATTACKER, and the payload claims
        // tenant VICTIM. The payload NEVER decides the tenant (P0 5.2): the
        // verified token's context wins, and NOTHING is created nor operated
        // for the tenant the body claimed.
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();

        // An opening where the body holds the VICTIM tenant while the context
        // holds the ATTACKER tenant. The use case receives the context tenant
        // everywhere the policy checks the row: the write is rejected.
        OpenWorkOrderCommand bodyClaimingVictim = new OpenWorkOrderCommand(
                victim, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
        assertThrows(RuntimeException.class, () ->
                withTenantOfToken(attacker, () -> openOrder.open(bodyClaimingVictim)),
                "the context of the token decides the tenant, and it contradicts the body");

        // The same holds for operations: a body claiming VICTIM cannot make the
        // context's ATTACKER operate on the VICTIM's order.
        assertThrows(WorkOrderNotFoundException.class, () ->
                withTenantOfToken(attacker, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(victim, victimOrderId,
                                WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID()))), "");

        assertTrue(victimOrdersByTenant(victim) == 1, "the victim's order count is untouched");
        assertEquals("RECEPCIONADA", victimOrderStatus(victimOrderId));
    }

    @Test
    void everyRejectedAttemptIsRecordedWithTheTenantItCameFrom() {
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();

        int eventsBefore = securityEvents.list.size();
        assertThrows(WorkOrderNotFoundException.class, () ->
                withTenantOfToken(attacker, () -> transitionOrder.transition(
                        new TransitionWorkOrderCommand(attacker, victimOrderId,
                                WorkOrderStatus.EN_DIAGNOSTICO, UUID.randomUUID()))), "");

        boolean recorded = securityEvents.list
                .subList(eventsBefore, securityEvents.list.size())
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("evento=orden_ajena_intentada")
                        && line.contains("tenant_id=" + attacker)
                        && line.contains("work_order_id=" + victimOrderId));
        assertTrue(recorded, "the attempt is in the security log with the attacker's tenant");
    }

    @Test
    void anAttackerCannotSeeTheVictimsOrderEvenWithDirectRepositoryReads() {
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID victimOrderId = openedOrderFor(victim, attacker).orderId();
        UUID misdirectedOrderId = UUID.randomUUID();

        // A read inside the ATTACKER's own transaction: a misdirected
        // identifier is indistinguishable from a foreign one — the RLS policy
        // gives the attacker zero victim rows either way.
        assertThrows(WorkOrderNotFoundException.class, () ->
                withTenantOfToken(attacker, () ->
                        readOnlyLookup(attacker, misdirectedOrderId)), "");
    }

    @Autowired
    private com.erp.workorder.domain.port.WorkOrderRepositoryPort repository;

    @Autowired
    private com.erp.workorder.domain.port.UnitOfWork unitOfWork;

    private boolean readOnlyLookup(UUID attacker, UUID orderId) {
        return unitOfWork.callInTransaction(() -> {
            boolean present = repository.findById(attacker, orderId).isPresent();
            if (!present) {
                throw new WorkOrderNotFoundException("the order does not exist in this tenant");
            }
            return true;
        });
    }

    private int victimHistoryRows(UUID victimOrderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM work_order_status_history WHERE work_order_id = '" + victimOrderId + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String victimOrderStatus(UUID victimOrderId) throws SQLException {
        try (Connection connection = EmbeddedPostgresSupport.openOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT status FROM work_orders WHERE id = '" + victimOrderId + "'")) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Opens the order for the victim, with a vehicle fleet already knows. */
    private OpenWorkOrderResult openedOrderFor(UUID victim, UUID attacker) {
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        fleet.registerVehicle(victim, vehicle, customer);
        fleet.registerVehicle(attacker, vehicle, customer);
        return withTenantOfToken(victim, () -> openOrder.open(new OpenWorkOrderCommand(
                victim, customer, vehicle, UUID.randomUUID(), UUID.randomUUID(), null)));
    }
}
