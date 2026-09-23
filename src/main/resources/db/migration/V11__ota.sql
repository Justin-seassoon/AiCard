-- OTA 固定配套发布：发布 + 固件包 + 设备档案扩展
CREATE TABLE ota_release (
    id BIGSERIAL PRIMARY KEY,
    update_id VARCHAR(64) NOT NULL UNIQUE,
    policy VARCHAR(16) NOT NULL,
    target_p4_version VARCHAR(31) NOT NULL,
    target_c5_version VARCHAR(31) NOT NULL,
    release_notes VARCHAR(256),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ota_package (
    id BIGSERIAL PRIMARY KEY,
    release_id BIGINT NOT NULL REFERENCES ota_release(id),
    module VARCHAR(4) NOT NULL,
    target_version VARCHAR(31) NOT NULL,
    image_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL,
    UNIQUE (release_id, module)
);

CREATE INDEX idx_ota_package_release ON ota_package(release_id);

ALTER TABLE device ADD COLUMN product_model VARCHAR(32);
ALTER TABLE device ADD COLUMN hardware_version VARCHAR(32);
ALTER TABLE device ADD COLUMN last_p4_version VARCHAR(31);
ALTER TABLE device ADD COLUMN last_c5_version VARCHAR(31);
