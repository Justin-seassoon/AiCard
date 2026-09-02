-- DEMO SEED：联调用测试设备（生产环境删除 / 替换）
-- 应用启动后 ODM 可用下面凭据连 WSS：
--   device_id = demo-device-001
--   token     = demo-token-001
INSERT INTO customer (code, name) VALUES ('DEMO-CUSTOMER', 'デモ顧客')
ON CONFLICT (code) DO NOTHING;

INSERT INTO store (code, customer_id, name)
SELECT 'DEMO-STORE', c.id, 'デモ店舗'
FROM customer c WHERE c.code = 'DEMO-CUSTOMER'
ON CONFLICT (code) DO NOTHING;

INSERT INTO device (device_id, customer_id, store_id, token, staff_language)
SELECT 'demo-device-001', s.customer_id, s.id, 'demo-token-001', 'ja-JP'
FROM store s WHERE s.code = 'DEMO-STORE'
ON CONFLICT (device_id) DO NOTHING;
