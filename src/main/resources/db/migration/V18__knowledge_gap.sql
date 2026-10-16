-- 自生长知识（①）：知识缺口池
-- 来源：qa_log(status='no_match') 聚合而来；经人工审核后才写入 document/chunk 入库。
CREATE TABLE knowledge_gap (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    domain VARCHAR(16) NOT NULL DEFAULT 'skb',
    question TEXT NOT NULL,                 -- 代表性问法（最早见到的）
    question_key VARCHAR(64) NOT NULL,      -- 归一化问句的 SHA-256（去重聚合键）
    occurrences INT NOT NULL DEFAULT 1,     -- 该问法的 no_match 次数
    status VARCHAR(16) NOT NULL DEFAULT 'open',  -- open | drafted | approved | rejected
    draft_answer TEXT,
    draft_refs TEXT,                        -- 出处（documentTitle@version#chunkId，逗号分隔）
    resolved_document_id BIGINT,            -- 通过后入库的 document id
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, store_id, domain, question_key)
);

CREATE INDEX idx_knowledge_gap_tenant_status ON knowledge_gap(customer_id, store_id, domain, status);
