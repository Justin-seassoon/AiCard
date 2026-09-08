CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'published',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_customer_store ON document(customer_id, store_id);

CREATE TABLE chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id),
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    text TEXT NOT NULL,
    embedding vector(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_customer_store ON chunk(customer_id, store_id);
