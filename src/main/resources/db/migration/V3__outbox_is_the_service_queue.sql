-- =============================================================================
-- WORK ORDER SERVICE - V3: THE OUTBOX IS THE SERVICE'S INTERNAL QUEUE
-- =============================================================================
-- The relay sweeps the pending events of ALL tenants: it publishes rows it
-- has not yet seen, so it cannot know each event's tenant before reading
-- them. A tenant-isolated outbox would make the sweep physically impossible
-- under the strict policy, and no REST endpoint exposes the queue to the
-- tenants. This is the platform's mature criterion: the inventory's
-- stock_outbox and the tenant service's tenant_outbox carry no tenant
-- isolation either. The tenant isolation lives where the tenant reads its
-- own data: work_orders, work_order_items, work_order_status_history and
-- work_order_sequences keep ENABLE + FORCE ROW LEVEL SECURITY and their
-- strict policies.
-- =============================================================================

DROP POLICY IF EXISTS work_order_outbox_tenant_isolation ON work_order_outbox;
ALTER TABLE work_order_outbox NO FORCE ROW LEVEL SECURITY;
ALTER TABLE work_order_outbox DISABLE ROW LEVEL SECURITY;
