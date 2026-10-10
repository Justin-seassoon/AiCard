-- 后台可配配置项（全局 / 租户 / 门店三级覆盖）
CREATE TABLE system_config (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(64) NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    customer_id BIGINT,          -- NULL = 全局
    store_id BIGINT,             -- NULL = 租户级；非空 = 门店级
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, config_key)
);
