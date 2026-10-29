-- 通用设备诊断日志：设备上传的排障日志只存共享卷，本表存索引与保留期。
CREATE TABLE device_log (
    id            BIGSERIAL PRIMARY KEY,
    log_id        VARCHAR(64)  NOT NULL UNIQUE,
    device_id     VARCHAR(64)  NOT NULL,
    customer_id   BIGINT       NOT NULL,
    store_id      BIGINT       NOT NULL,
    meeting_id    VARCHAR(64),
    fw_version    VARCHAR(32),
    level         VARCHAR(16)  NOT NULL DEFAULT 'info',
    captured_from TIMESTAMPTZ,
    captured_to   TIMESTAMPTZ,
    size_bytes    BIGINT       NOT NULL,
    raw_size_bytes BIGINT      NOT NULL,
    compressed    BOOLEAN      NOT NULL DEFAULT FALSE,
    storage_key   VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_device_log_tenant ON device_log (customer_id, store_id, created_at DESC);
CREATE INDEX idx_device_log_device ON device_log (device_id, created_at DESC);
CREATE INDEX idx_device_log_expiry ON device_log (expires_at) WHERE deleted_at IS NULL;
