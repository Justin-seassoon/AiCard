#!/bin/bash
# 知识库重灌脚本：清空 → 灌 SKB → 灌 VKB → 验证 → 正常启动
# 前置：已完成 §7.1~7.4（上传 jar、重建镜像、导出 /tmp/aicard.env、停容器清空表）
# 用法：scp 到服务器后 bash /tmp/reseed.sh
set -e

echo "=== 灌 SKB（便利店+连锁酒店）==="
docker run -d --rm --name seed-skb --env-file /tmp/aicard.env -v /tmp/aicard:/app/kb -p 18080:8080 \
  aicard:latest \
  --skb.seed.enabled=true --skb.seed.domain=skb \
  --skb.seed.files=/app/kb/演示知识库-便利店与免税店-日语版.md,/app/kb/演示知识库-连锁酒店-日语版.md \
  --skb.seed.customer-id=1 --skb.seed.store-id=1

for i in $(seq 1 180); do
  cnt=$(docker exec aicard-postgres psql -U root -d db_ai_card -t -c "SELECT count(*) FROM document WHERE domain='skb';" 2>/dev/null | tr -d ' ')
  if [ "$cnt" = "38" ]; then echo "SKB_SEEDED=$cnt"; break; fi
  sleep 3
done
docker logs seed-skb 2>&1 | grep -E '灌库|ERROR|Exception' | tail -5
docker stop seed-skb

echo "=== 灌 VKB（游客FAQ）==="
docker run -d --rm --name seed-vkb --env-file /tmp/aicard.env -v /tmp/aicard:/app/kb -p 18081:8080 \
  aicard:latest \
  --skb.seed.enabled=true --skb.seed.domain=vkb \
  --skb.seed.files=/app/kb/VKB游客FAQ-日语版.md \
  --skb.seed.customer-id=1 --skb.seed.store-id=1

for i in $(seq 1 180); do
  cnt=$(docker exec aicard-postgres psql -U root -d db_ai_card -t -c "SELECT count(*) FROM document WHERE domain='vkb';" 2>/dev/null | tr -d ' ')
  if [ "$cnt" = "21" ]; then echo "VKB_SEEDED=$cnt"; break; fi
  sleep 3
done
docker logs seed-vkb 2>&1 | grep -E '灌库|ERROR|Exception' | tail -5
docker stop seed-vkb

echo "=== 验证 ==="
docker exec aicard-postgres psql -U root -d db_ai_card -c "SELECT domain, count(*) AS docs FROM document GROUP BY domain;"
docker exec aicard-postgres psql -U root -d db_ai_card -c "SELECT count(*) AS chunks FROM chunk;"

echo "=== 正常启动 ==="
docker run -d --name aicard-app --restart always --env-file /tmp/aicard.env -p 8080:8080 aicard:latest

echo "ALL_DONE"
