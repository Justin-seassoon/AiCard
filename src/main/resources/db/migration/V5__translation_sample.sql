CREATE TABLE translation_sample (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    src_lang VARCHAR(16),
    tgt_lang VARCHAR(16),
    source_text TEXT,
    translated_text TEXT,
    lid_confidence DOUBLE PRECISION,
    source_side VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sample_customer_store ON translation_sample(customer_id, store_id);
