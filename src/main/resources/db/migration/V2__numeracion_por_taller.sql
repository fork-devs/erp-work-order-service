-- =============================================================================
-- ERP WORK ORDER SERVICE - V2: NUMERACION RESERVADA POR TALLER
-- =============================================================================
-- El numero de orden se RESERVA en el momento de abrir la orden, con el mismo
-- criterio que el ADR-002 fijo para los comprobantes fiscales y que el
-- hallazgo 6.43 cerro en el hub: un contador por taller que solo avanza, no
-- un COUNT sobre las filas (dos aperturas simultaneas obtendrian el mismo
-- numero, y el hueco de una orden cancelada se reutilizaria).
--
-- La tabla alberga UNA fila por taller. El caso de uso la reclama con
-- "ON CONFLICT DO NOTHING" (un taller sin ordenes anteriores la recibe sin
-- carrera) y avanza su contador con "FOR UPDATE", de modo que dos aperturas
-- del mismo taller se serializan: la segunda espera y ve el numero ya
-- consumido. La unicidad (tenant_id, number) de V1 queda como defensa en
-- profundidad.
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
