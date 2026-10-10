-- 问答旁路日志（SKB 员工问答 / VKB 游客问答）
CREATE TABLE qa_log (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    device_id VARCHAR(64),
    session_id VARCHAR(64),
    turn_id VARCHAR(64),
    source VARCHAR(8) NOT NULL,
    question TEXT,
    answer TEXT,
    status VARCHAR(16) NOT NULL,
    source_refs TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_qa_log_device_created ON qa_log(customer_id, store_id, device_id, created_at);
