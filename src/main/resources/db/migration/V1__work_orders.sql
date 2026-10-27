-- =============================================================================
-- ERP WORK ORDER SERVICE - V1: WORK ORDERS, ISOLATION AND EXECUTION ROLE
-- =============================================================================
-- The four tables of the documented model (doc-database-diagrams.md, S4) with
-- two corrections regarding the original DBML:
--
--   1. work_orders.branch_id NOT NULL: the inventory stock saga requires the
--      branch of the part (branchId is mandatory in ReserveStockCommand), and
--      the order declares it when it opens. The reference is LOGICAL (no
--      cross-service FK: every service owns its schema).
--   2. work_order_outbox in its mature shape (the same as the inventory in
--      stock_outbox): each event carries a unique event_id so consumers can
--      deduplicate, plus estado, attempts and next_attempt_at for the relay
--      with retries and dead-letter. The DBML's published boolean column
--      allowed neither of them (finding 7.21).
--
-- Row-Level Security is ENABLED and FORCED. FORCE alone only makes the
-- policy apply to the table OWNER; enabling is a separate statement and both
-- are required.
--
-- The tenant context is read WITHOUT the "missing_ok" flag: without a
-- context the query fails explicitly instead of silently returning zero rows
-- (findings P1 6.14 / P1 6.53).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. work_orders: the order with its per-tenant reserved number
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_orders (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    number      VARCHAR(20) NOT NULL,
    customer_id UUID NOT NULL,
    vehicle_id  UUID NOT NULL,
    branch_id   UUID NOT NULL,
    mechanic_id UUID,
    status      VARCHAR(24) NOT NULL DEFAULT 'RECEPCIONADA',
    diagnosis   TEXT,
    opened_at   TIMESTAMP NOT NULL DEFAULT now(),
    closed_at   TIMESTAMP,
    CONSTRAINT uq_work_order_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT ck_work_order_status CHECK (
        status IN ('RECEPCIONADA', 'EN_DIAGNOSTICO', 'COTIZADA', 'EN_EJECUCION',
                   'EN_CONTROL_CALIDAD', 'LISTA', 'ENTREGADA', 'RECHAZADA', 'CANCELADA')
    )
);

ALTER TABLE work_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_orders FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_tenant_isolation ON work_orders;
CREATE POLICY work_order_tenant_isolation ON work_orders
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- -----------------------------------------------------------------------------
-- 2. work_order_items: the parts of the order, as logical references
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    spare_part_id UUID NOT NULL,
    quantity      INTEGER NOT NULL,
    labor_price   NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_order_item_quantity CHECK (quantity > 0)
);

ALTER TABLE work_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_items FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_item_tenant_isolation ON work_order_items;
CREATE POLICY work_order_item_tenant_isolation ON work_order_items
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_items_order ON work_order_items (tenant_id, work_order_id);

-- -----------------------------------------------------------------------------
-- 3. work_order_status_history: the immutable history, with its author
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_status_history (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    from_status   VARCHAR(24),
    to_status     VARCHAR(24) NOT NULL,
    changed_by    UUID,
    changed_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_order_history_status CHECK (
        to_status IN ('RECEPCIONADA', 'EN_DIAGNOSTICO', 'COTIZADA', 'EN_EJECUCION',
                      'EN_CONTROL_CALIDAD', 'LISTA', 'ENTREGADA', 'RECHAZADA', 'CANCELADA')
    )
);

ALTER TABLE work_order_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_status_history FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_history_tenant_isolation ON work_order_status_history;
CREATE POLICY work_order_history_tenant_isolation ON work_order_status_history
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_history_order ON work_order_status_history (tenant_id, work_order_id, changed_at);

-- -----------------------------------------------------------------------------
-- 4. work_order_outbox: events travel together with the status change
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(40) NOT NULL,
    event_id        UUID NOT NULL,
    payload         JSONB NOT NULL,
    estado          VARCHAR(12) NOT NULL DEFAULT 'PENDIENTE',
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_work_order_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_work_order_outbox_estado CHECK (
        estado IN ('PENDIENTE', 'PUBLICADO', 'DLQ')
    ),
    CONSTRAINT ck_work_order_outbox_event_type CHECK (
        event_type IN ('WorkOrderCompleted', 'WorkOrderCancelled')
    )
);

ALTER TABLE work_order_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_outbox FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_outbox_tenant_isolation ON work_order_outbox;
CREATE POLICY work_order_outbox_tenant_isolation ON work_order_outbox
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_outbox_pending
    ON work_order_outbox (estado, next_attempt_at);

-- -----------------------------------------------------------------------------
-- 5. Execution role: connects the application, is not the owner, bypasses nothing
-- -----------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_work_order_app') THEN
        EXECUTE format(
            'CREATE ROLE erp_work_order_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB '
            'NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            '${appRolePassword}'
        );
    END IF;
END
$$;

-- Without USAGE on the schema the role resolves no object, even with table grants.
GRANT USAGE ON SCHEMA public TO erp_work_order_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_orders TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_items TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_status_history TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_outbox TO erp_work_order_app;

-- Check that the execution role cannot bypass the isolation policy: if it
-- could, everything above would be inert.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = 'erp_work_order_app' AND rolbypassrls
    ) THEN
        RAISE EXCEPTION 'The erp_work_order_app role must not have BYPASSRLS: the isolation policy would be bypassable';
    END IF;
END
$$;
