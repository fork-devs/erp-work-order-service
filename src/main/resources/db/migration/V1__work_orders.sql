-- =============================================================================
-- ERP WORK ORDER SERVICE - V1: ORDENES DE TRABAJO, AISLAMIENTO Y ROL DE EJECUCION
-- =============================================================================
-- Las cuatro tablas del modelo documentado (doc-database-diagrams.md, S4) con
-- dos correcciones respecto al DBML original:
--
--   1. work_orders.branch_id NOT NULL: la saga de stock del inventario exige
--      la sucursal del repuesto (branchId es obligatorio en
--      ReserveStockCommand), y la orden la declara al abrirse. La referencia
--      es LOGICA (sin FK cruzada: cada servicio es dueno de su esquema).
--   2. work_order_outbox en su forma madura (la misma del inventario en
--      stock_outbox): evento con event_id unico para que el consumidor
--      deduplique, estado, intentos y next_attempt_at para el relay con
--      reintentos y cola de muertos. La columna published boolean del DBML
--      no permite ninguna de las dos cosas (hallazgo 7.21).--
-- La Row-Level Security se HABILITA y se FUERZA. FORCE solo obliga a que la
-- politica se aplique tambien al propietario; habilitarla es una sentencia
-- distinta y hacen falta las dos.
--
-- El contexto de taller se lee SIN el flag "missing_ok": sin contexto, la
-- consulta falla de forma explicita en lugar de devolver cero filas en
-- silencio (hallazgo P1 6.14 / P1 6.53).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. work_orders: la orden con su numero reservado por taller
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
-- 2. work_order_items: los repuestos de la orden, como referencias logicas
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
-- 3. work_order_status_history: la historia inmutable, con su autor
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
-- 4. work_order_outbox: los eventos viajan junto al cambio de estado
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

CREATE INDEX IF NOT EXISTS ix_work_order_outbox_pendientes
    ON work_order_outbox (estado, next_attempt_at);

-- -----------------------------------------------------------------------------
-- 5. Rol de ejecucion: conecta la aplicacion, no es propietario y no elude nada
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

-- Sin USAGE sobre el esquema, el rol no resuelve ningun objeto aunque tenga
-- privilegios sobre las tablas.
GRANT USAGE ON SCHEMA public TO erp_work_order_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_orders TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_items TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_status_history TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_outbox TO erp_work_order_app;

-- Comprobacion de que el rol de ejecucion no puede eludir la politica de
-- aislamiento: si pudiera, todo lo anterior seria inerte.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = 'erp_work_order_app' AND rolbypassrls
    ) THEN
        RAISE EXCEPTION 'El rol erp_work_order_app no debe tener BYPASSRLS: la politica de aislamiento seria eludible';
    END IF;
END
$$;
