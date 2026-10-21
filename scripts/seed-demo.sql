-- seed-demo.sql — 演示用「未命中问答」种子（用于生成知识缺口）
-- 幂等：已存在同租户同问句的 no_match 记录则跳过。
INSERT INTO qa_log(customer_id, store_id, source, question, status)
SELECT 1, 1, 'skb', q, 'no_match'
FROM (VALUES
    ('駐車場はありますか？'),
    ('領収書の再発行はできますか？'),
    ('駐車場はありますか'),
    ('Wi-Fi のパスワードは何ですか？')
) AS t(q)
WHERE NOT EXISTS (
    SELECT 1 FROM qa_log x
    WHERE x.customer_id = 1 AND x.store_id = 1 AND x.status = 'no_match' AND x.question = t.q
);
