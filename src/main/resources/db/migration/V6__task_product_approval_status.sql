ALTER TABLE work_order_task_products
    ADD COLUMN IF NOT EXISTS approval_status VARCHAR(35) NOT NULL DEFAULT 'CUSTOMER_APPROVED';

ALTER TABLE work_order_task_products
    DROP CONSTRAINT IF EXISTS ck_work_order_task_product_approval_status;

ALTER TABLE work_order_task_products
    ADD CONSTRAINT ck_work_order_task_product_approval_status CHECK (
        approval_status IN ('PENDING_SUPERVISOR_CHECK', 'SENT_TO_CUSTOMER', 'CUSTOMER_APPROVED', 'CUSTOMER_REJECTED')
    );
