CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_store_customer ON store(customer_id);

CREATE TABLE device (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    store_id BIGINT NOT NULL REFERENCES store(id),
    token VARCHAR(255) NOT NULL,
    staff_language VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_customer_store ON device(customer_id, store_id);
