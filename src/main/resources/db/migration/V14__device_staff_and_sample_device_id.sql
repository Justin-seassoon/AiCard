-- 设备即人（员工姓名/工号可选字段）+ 采集样本补 device_id 以支持按设备聚合
ALTER TABLE device ADD COLUMN staff_name VARCHAR(64);
ALTER TABLE device ADD COLUMN staff_no VARCHAR(64);

ALTER TABLE translation_sample ADD COLUMN device_id VARCHAR(64);
CREATE INDEX idx_translation_sample_device_created ON translation_sample(device_id, created_at);
