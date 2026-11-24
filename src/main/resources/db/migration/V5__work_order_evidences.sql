CREATE TABLE IF NOT EXISTS work_order_evidences (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    work_order_id   UUID NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    stage           VARCHAR(30) NOT NULL,
    media_id        VARCHAR(100) NOT NULL,
    image_url       VARCHAR(500) NOT NULL,
    caption         TEXT,
    uploaded_by     UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE work_order_evidences ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_evidences FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_evidences_tenant_isolation ON work_order_evidences;
CREATE POLICY work_order_evidences_tenant_isolation ON work_order_evidences
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX IF NOT EXISTS ix_work_order_evidences_order
    ON work_order_evidences (tenant_id, work_order_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE work_order_evidences TO erp_work_order_app;
