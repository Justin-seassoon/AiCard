-- ③ 文档处理深化：上传文件的解析结果（未落盘，解析后存结构化 JSON）
CREATE TABLE doc_upload (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    mime VARCHAR(128),
    parsed JSONB,
    warnings JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_doc_upload_tenant ON doc_upload(customer_id, store_id, created_at DESC);
