-- 会议录音设备接口契约（P1-a）：start 上报的固件/能力/触发/合规留痕。
ALTER TABLE meeting ADD COLUMN fw_version VARCHAR(32);
ALTER TABLE meeting ADD COLUMN features JSONB;
ALTER TABLE meeting ADD COLUMN trigger_type VARCHAR(32);
ALTER TABLE meeting ADD COLUMN notice_played BOOLEAN;
ALTER TABLE meeting ADD COLUMN notice_version VARCHAR(32);
