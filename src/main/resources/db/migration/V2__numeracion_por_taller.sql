-- =============================================================================
-- ERP WORK ORDER SERVICE - V2: PER-TENANT RESERVED NUMBERING
-- =============================================================================
-- The order number is RESERVED the moment the order opens, with the same rule
-- ADR-002 fixed for fiscal documents and finding 6.43 closed at the hub: a
-- counter per tenant that only advances, not a COUNT over the rows (two
-- simultaneous openers would get the same number, and a cancelled order's
-- gap would be reused).
--
-- The table holds ONE row per tenant. The use case claims it with
-- "ON CONFLICT DO NOTHING" (a tenant with no previous orders gets its row
-- without a race) and advances the counter with "FOR UPDATE", so two
-- openings of the same tenant serialize: the second waits and sees the
-- number already consumed. The (tenant_id, number) uniqueness of V1 stays as
-- defense in depth.
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_order_sequences (
    tenant_id   UUID PRIMARY KEY,
    last_number INTEGER NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

ALTER TABLE work_order_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_sequences FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS work_order_sequence_tenant_isolation ON work_order_sequences;
CREATE POLICY work_order_sequence_tenant_isolation ON work_order_sequences
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE work_order_sequences TO erp_work_order_app;
