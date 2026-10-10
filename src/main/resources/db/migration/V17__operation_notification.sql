-- 运营通知/提醒（定时主动推送）+ 投递回执
CREATE TABLE operation_notification (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    title VARCHAR(255),
    content TEXT NOT NULL,
    source_language VARCHAR(16) NOT NULL,
    target_type VARCHAR(8) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(8) NOT NULL,
    schedule_at TIMESTAMPTZ,
    cron_expr VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'scheduled',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_due ON operation_notification(customer_id, store_id, status, schedule_at);

CREATE TABLE notification_delivery (
    id BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES operation_notification(id),
    device_id VARCHAR(64) NOT NULL,
    translated_text TEXT,
    target_language VARCHAR(16),
    delivered_at TIMESTAMPTZ,
    UNIQUE (notification_id, device_id)
);

CREATE INDEX idx_notification_delivery_device ON notification_delivery(device_id, delivered_at);
