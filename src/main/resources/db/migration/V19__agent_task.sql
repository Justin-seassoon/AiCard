-- ② agent 执行：多步任务状态机（持久化 + 步骤 + 产物）
CREATE TABLE agent_task (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    goal VARCHAR(32) NOT NULL,                 -- alert_report | usage_report
    mode VARCHAR(8) NOT NULL DEFAULT 'rule',   -- rule | llm
    status VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending | running | done | failed
    steps JSONB,                               -- [{tool,args,status,output,error,attempts,ms}]
    result TEXT,
    error TEXT,
    artifact_name VARCHAR(128),
    artifact_mime VARCHAR(96),
    artifact_base64 TEXT,                      -- 交付物（demo 规模，base64 存库）
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_task_tenant ON agent_task(customer_id, store_id, created_at DESC);
