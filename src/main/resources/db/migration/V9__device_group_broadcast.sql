-- 分组多语言广播：群组 + 广播 + 投递回执（四层租户隔离：customer_id + store_id）
CREATE TABLE device_group (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    store_id BIGINT NOT NULL REFERENCES store(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, code)
);

CREATE INDEX idx_device_group_customer_store ON device_group(customer_id, store_id);

CREATE TABLE device_group_member (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES device_group(id),
    device_id VARCHAR(64) NOT NULL REFERENCES device(device_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, device_id)
);

CREATE INDEX idx_device_group_member_group ON device_group_member(group_id);
CREATE INDEX idx_device_group_member_device ON device_group_member(device_id);

CREATE TABLE broadcast (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES device_group(id),
    source_text TEXT NOT NULL,
    source_language VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_broadcast_group ON broadcast(group_id);

CREATE TABLE broadcast_delivery (
    id BIGSERIAL PRIMARY KEY,
    broadcast_id BIGINT NOT NULL REFERENCES broadcast(id),
    device_id VARCHAR(64) NOT NULL REFERENCES device(device_id),
    translated_text TEXT NOT NULL,
    target_language VARCHAR(16) NOT NULL,
    delivered_at TIMESTAMPTZ,
    ack_at TIMESTAMPTZ,
    UNIQUE (broadcast_id, device_id)
);

CREATE INDEX idx_broadcast_delivery_broadcast ON broadcast_delivery(broadcast_id);
CREATE INDEX idx_broadcast_delivery_device ON broadcast_delivery(device_id);
