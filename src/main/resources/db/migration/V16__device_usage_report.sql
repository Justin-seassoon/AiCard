-- 设备用量周报/月报快照（按设备聚合，唯一键支持覆盖重生成）
CREATE TABLE device_usage_report (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    period_type VARCHAR(8) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    turn_count INT,
    session_count INT,
    qa_hit_count INT,
    active_days INT,
    metrics JSONB,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_id, period_type, period_start)
);

CREATE INDEX idx_report_device_period ON device_usage_report(device_id, period_type, period_start);
CREATE INDEX idx_report_customer_store ON device_usage_report(customer_id, store_id);
