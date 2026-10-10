-- 制造维修上报 + 点检闭环（纯后台录入，不依赖设备端改动）
CREATE TABLE maintenance_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) REFERENCES device(device_id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    reporter VARCHAR(64),
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, order_no)
);

CREATE INDEX idx_maintenance_order_customer_store ON maintenance_order(customer_id, store_id);
CREATE INDEX idx_maintenance_order_device ON maintenance_order(device_id);
CREATE INDEX idx_maintenance_order_status ON maintenance_order(customer_id, store_id, status);

CREATE TABLE inspection_task (
    id BIGSERIAL PRIMARY KEY,
    task_no VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) REFERENCES device(device_id),
    title VARCHAR(255) NOT NULL,
    checklist JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    scheduled_date DATE,
    assignee VARCHAR(64),
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, task_no)
);

CREATE INDEX idx_inspection_task_customer_store ON inspection_task(customer_id, store_id);
CREATE INDEX idx_inspection_task_device ON inspection_task(device_id);
CREATE INDEX idx_inspection_task_status ON inspection_task(customer_id, store_id, status);

CREATE TABLE inspection_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES inspection_task(id),
    device_id VARCHAR(64),
    results JSONB NOT NULL,
    status VARCHAR(16),
    inspected_by VARCHAR(64),
    inspected_at TIMESTAMPTZ,
    note TEXT,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_record_task ON inspection_record(task_id);
CREATE INDEX idx_inspection_record_customer_store ON inspection_record(customer_id, store_id);
