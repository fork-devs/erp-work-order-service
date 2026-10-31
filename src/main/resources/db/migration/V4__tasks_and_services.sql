-- =============================================================================
-- ERP WORK ORDER SERVICE - V4: WORKSHOP SERVICES, TASKS AND TASK PRODUCTS
-- =============================================================================
-- Extends the work order service to support:
--   1. workshop_services: Service catalog scoped per branch and tenant
--   2. work_order_tasks: Operational task breakdown per work order
--   3. work_order_task_products: Spare parts allocated to tasks with stock reservations
--
-- Row-Level Security is ENABLED and FORCED on all three tables.
-- The app execution role erp_work_order_app receives full CRUD permissions.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. workshop_services: branch-scoped labor catalog
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS workshop_services (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL,
    branch_id  UUID NOT NULL,
    name       VARCHAR(255) NOT NULL,
    price      NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_workshop_service_price CHECK (price >= 0)
);

ALTER TABLE workshop_services ENABLE ROW LEVEL SECURITY;
ALTER TABLE workshop_services FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS workshop_service_tenant_isolation ON workshop_services;
CREATE POLICY workshop_service_tenant_isolation ON workshop_services
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_workshop_services_tenant_branch ON workshop_services (tenant_id, branch_id);

-- -----------------------------------------------------------------------------
-- 2. work_order_tasks: tasks attached to a work order
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_tasks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    work_order_id        UUID NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    service_id           UUID NOT NULL REFERENCES workshop_services(id),
    assigned_mechanic_id UUID,
    description          TEXT NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    evidence_images      TEXT[],
    started_at           TIMESTAMP,
    completed_at         TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_order_task_status CHECK (
        status IN ('PENDING', 'DOING', 'COMPLETED')
    )
);

ALTER TABLE work_order_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_tasks FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_task_tenant_isolation ON work_order_tasks;
CREATE POLICY work_order_task_tenant_isolation ON work_order_tasks
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_tasks_order ON work_order_tasks (tenant_id, work_order_id);
CREATE INDEX IF NOT EXISTS ix_work_order_tasks_service ON work_order_tasks (tenant_id, service_id);

-- -----------------------------------------------------------------------------
-- 3. work_order_task_products: spare parts allocated to a task
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_task_products (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    task_id      UUID NOT NULL REFERENCES work_order_tasks(id) ON DELETE CASCADE,
    product_id   UUID NOT NULL,
    quantity     INTEGER NOT NULL,
    unit_price   NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_order_task_product_quantity CHECK (quantity > 0)
);

ALTER TABLE work_order_task_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_task_products FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_task_product_tenant_isolation ON work_order_task_products;
CREATE POLICY work_order_task_product_tenant_isolation ON work_order_task_products
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_task_products_task ON work_order_task_products (tenant_id, task_id);

-- -----------------------------------------------------------------------------
-- 4. Grants for erp_work_order_app
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE workshop_services TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_tasks TO erp_work_order_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_task_products TO erp_work_order_app;
