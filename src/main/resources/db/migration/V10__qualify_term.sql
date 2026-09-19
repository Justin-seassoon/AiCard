-- 质量门控词表：品牌词表 + 短词白名单（四层租户隔离：customer_id + store_id）
CREATE TABLE qualify_term (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    store_id BIGINT NOT NULL REFERENCES store(id),
    type VARCHAR(16) NOT NULL,           -- whitelist | brand
    term VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, type, term)
);

CREATE INDEX idx_qualify_term_tenant_type ON qualify_term(customer_id, store_id, type);
