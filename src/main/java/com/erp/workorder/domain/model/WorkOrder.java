package com.erp.workorder.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The work order aggregate. The state machine owns it: a change the machine
 * does not declare is rejected, the history accumulates and a terminal state
 * never reopens.
 *
 * <p>The saga does NOT live here: the aggregate declares the transition and
 * the history; coupling the inventory call is the application use case, in
 * the same transactional boundary, because transport guarantees (deadline,
 * circuit breaker, idempotency key) belong to the bridge with the saga, not
 * to the model.
 */
public class WorkOrder {

    private final UUID id;
    private final TenantId tenantId;
    private final WorkOrderNumber number;
    private final CustomerRef customer;
    private final VehicleRef vehicle;
    private final BranchId branch;
    private MechanicRef mechanic;
    private WorkOrderStatus status;
    private String diagnosis;
    private final Instant openedAt;
    private Instant closedAt;
    private final List<WorkOrderItem> items = new ArrayList<>();
    private final List<StatusChange> history = new ArrayList<>();
    /** How many history entries the persistence layer already wrote; the rest are pending. */
    private int persistedHistorySize;

    /** Creates the order at RECEPCIONADA, its first status, with the opening history entry. */
    public static WorkOrder opened(
            UUID id,
            TenantId tenantId,
            WorkOrderNumber number,
            CustomerRef customer,
            VehicleRef vehicle,
            BranchId branch,
            String diagnosis,
            Instant openedAt,
            UUID openedBy
    ) {
        return new WorkOrder(id, tenantId, number, customer, vehicle, branch,
                WorkOrderStatus.RECEPCIONADA, diagnosis, openedAt, openedBy);
    }

    /** Rehydrates from the persistence read view: same status and history exactly as stored. */
    public static WorkOrder rehydrated(
            UUID id,
            TenantId tenantId,
            WorkOrderNumber number,
            CustomerRef customer,
            VehicleRef vehicle,
            BranchId branch,
            MechanicRef mechanic,
            WorkOrderStatus status,
            String diagnosis,
            Instant openedAt,
            Instant closedAt,
            List<WorkOrderItem> items,
            List<StatusChange> history
    ) {
        WorkOrder order = new WorkOrder(id, tenantId, number, customer, vehicle, branch, status, diagnosis,
                openedAt, null);
        order.mechanic = mechanic;
        order.closedAt = closedAt;
        order.items.addAll(items);
        order.history.addAll(history);
        order.persistedHistorySize = order.history.size();
        return order;
    }

    private WorkOrder(
            UUID id,
            TenantId tenantId,
            WorkOrderNumber number,
            CustomerRef customer,
            VehicleRef vehicle,
            BranchId branch,
            WorkOrderStatus status,
            String diagnosis,
            Instant openedAt,
            UUID openedBy
    ) {
        if (id == null || tenantId == null || number == null || customer == null || vehicle == null || branch == null) {
            throw new IllegalArgumentException("a work order requires tenant, number, customer, vehicle and branch");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.number = number;
        this.customer = customer;
        this.vehicle = vehicle;
        this.branch = branch;
        this.status = status;
        this.diagnosis = diagnosis;
        this.openedAt = openedAt;
        this.history.add(new StatusChange(null, openedBy, openedAt));
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public WorkOrderNumber number() {
        return number;
    }

    public WorkOrderStatus status() {
        return status;
    }

    public CustomerRef customer() {
        return customer;
    }

    public VehicleRef vehicle() {
        return vehicle;
    }

    public BranchId branch() {
        return branch;
    }

    public Optional<MechanicRef> mechanic() {
        return Optional.ofNullable(mechanic);
    }

    public String diagnosis() {
        return diagnosis;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Optional<Instant> closedAt() {
        return Optional.ofNullable(closedAt);
    }

    public List<WorkOrderItem> items() {
        return Collections.unmodifiableList(items);
    }

    public List<StatusChange> history() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Applies the transition the state machine declares and leaves exactly one
     * history entry with its author and time. The rejection is explicit and
     * leaves no trace: the state machine enforces it.
     */
    public WorkOrderStatus.Transition transitionTo(WorkOrderStatus destination, UUID changedBy, Instant at) {
        WorkOrderStatus.Transition transition = status.requireTransitionTo(destination);
        status = destination;
        history.add(new StatusChange(transition, changedBy, at));
        if (destination.isTerminal()) {
            closedAt = at;
        }
        return transition;
    }

    /**
     * Parts live only before the quote: the stock guarantee comes from the
     * saga reservation when quoting, and adding parts afterwards would leave
     * items without a reservation; terminal states accept nothing.
     */
    public void addItem(WorkOrderItem item) {
        if (status == WorkOrderStatus.COTIZADA
                || status == WorkOrderStatus.EN_EJECUCION
                || status.isTerminal()) {
            throw new IllegalStateException("items of a " + status
                    + " order cannot change: the stock guarantee was claimed by the saga");
        }
        items.add(item);
    }

    /** Writes the workshop diagnosis while the order still happens to be under diagnosis. */
    public void diagnose(String diagnosis, MechanicRef assignedMechanic) {
        if (status != WorkOrderStatus.RECEPCIONADA && status != WorkOrderStatus.EN_DIAGNOSTICO) {
            throw new IllegalStateException("the diagnosis is written before quoting, not at " + status);
        }
        this.diagnosis = diagnosis;
        if (assignedMechanic != null) {
            this.mechanic = assignedMechanic;
        }
    }

    /** The reservation lives only at COTIZADA, so only there can it be released. */
    public boolean hasLiveReservation() {
        return status == WorkOrderStatus.COTIZADA;
    }

    /** The history entries the persistence layer has not written yet. */
    public List<StatusChange> unpersistedHistory() {
        return Collections.unmodifiableList(history.subList(persistedHistorySize, history.size()));
    }

    /** Called by the repository adapter once the pending history rows are written. */
    public void markHistoryPersisted() {
        persistedHistorySize = history.size();
    }
}
