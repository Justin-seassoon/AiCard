-- 会议录音与智能纪要（新增需求 · P0 云端）
-- 录音按 30 秒分片上传；会议结束后异步转写 → 结构化纪要 + 待办。
-- 音频文件本身存共享卷（meeting.storage-dir），本表只存元数据与分片清单。
CREATE TABLE meeting (
    id                 BIGSERIAL PRIMARY KEY,
    meeting_id         VARCHAR(64)  NOT NULL UNIQUE,
    client_key         VARCHAR(64),
    customer_id        BIGINT       NOT NULL,
    store_id           BIGINT       NOT NULL,
    device_id          VARCHAR(64)  NOT NULL,
    staff_name         VARCHAR(64),
    title              VARCHAR(255),
    status             VARCHAR(24)  NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    ended_at           TIMESTAMPTZ,
    duration_sec       INT,
    chunk_sec          INT          NOT NULL DEFAULT 30,
    codec              VARCHAR(16)  NOT NULL DEFAULT 'pcm16',
    sample_rate        INT          NOT NULL DEFAULT 16000,
    last_seq           INT,
    expected_last_seq  INT,
    missing_seqs       JSONB,
    transcript         TEXT,
    transcript_json    JSONB,
    minutes_json       JSONB,
    error_message      TEXT,
    audio_expires_at   TIMESTAMPTZ  NOT NULL,
    audio_deleted_at   TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_meeting_device_client_key UNIQUE (device_id, client_key)
);

CREATE INDEX idx_meeting_tenant ON meeting (customer_id, store_id, started_at DESC);
CREATE INDEX idx_meeting_device ON meeting (device_id, started_at DESC);
CREATE INDEX idx_meeting_expiry ON meeting (audio_expires_at) WHERE audio_deleted_at IS NULL;

CREATE TABLE meeting_chunk (
    id          BIGSERIAL PRIMARY KEY,
    meeting_id  VARCHAR(64)  NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    seq         INT          NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    sha256      VARCHAR(64),
    storage_key VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_meeting_chunk_seq UNIQUE (meeting_id, seq)
);

CREATE TABLE meeting_action_item (
    id          BIGSERIAL PRIMARY KEY,
    meeting_id  VARCHAR(64)  NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    customer_id BIGINT       NOT NULL,
    store_id    BIGINT       NOT NULL,
    task        TEXT         NOT NULL,
    owner       VARCHAR(128),
    due_date    VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'todo',
    source_ref  VARCHAR(64),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_meeting_action_tenant ON meeting_action_item (customer_id, store_id, status);
CREATE INDEX idx_meeting_action_meeting ON meeting_action_item (meeting_id, sort_order);
