#!/usr/bin/env bash
#
# seed-demo.sh — 给演示租户灌运营数据（维修单 / 点检），并触发一次知识缺口捕获。
# 用法: bash scripts/seed-demo.sh [base_url] [customerId] [storeId]
#
# 注：知识缺口来自 qa_log(no_match)，需先用 SQL 灌几条未命中问答（见 docs/开发指南）。
set -u
BASE="${1:-http://localhost:8080}"
CID="${2:-1}"
SID="${3:-1}"
H=(-H "X-Customer-Id: $CID" -H "X-Store-Id: $SID" -H 'Content-Type: application/json')

post() {
  echo -n "$1 -> "
  curl -s -m 10 "${H[@]}" -d "$2" "$BASE$1" | head -c 160
  echo
}

post /api/maintenance/orders '{"order_no":"DEMO-O1","title":"冷蔵庫から異音","description":"厨房の冷蔵庫","reporter":"田中"}'
post /api/maintenance/orders '{"order_no":"DEMO-O2","title":"エアコン故障","description":"2F 会議室","reporter":"佐藤"}'
post /api/maintenance/orders '{"order_no":"DEMO-O3","title":"天井からの漏水","description":"受付付近","reporter":"鈴木"}'
post /api/inspection/tasks '{"task_no":"DEMO-T1","title":"月次設備点検","checklist":"{}","assignee":"山田"}'
post /api/inspection/tasks '{"task_no":"DEMO-T2","title":"消防設備点検","checklist":"{}","assignee":"伊藤"}'

echo -n "gap capture -> "
curl -s -m 15 -H "X-Customer-Id: $CID" -H "X-Store-Id: $SID" -X POST \
  "$BASE/api/knowledge-gaps/capture?customerId=$CID&storeId=$SID"
echo
