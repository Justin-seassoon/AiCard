#!/usr/bin/env bash
#
# agent-eval.sh — ②-A 自然语言 agent 评测
# 对**已部署服务器**顺序提交 15 条日语指令，输出：完成率 / 产物率 / 平均步数 / 重试率 / 失败原因。
#
# 用法: bash scripts/agent-eval.sh [base_url] [customerId] [storeId] [mode]
# 例:   bash scripts/agent-eval.sh http://120.253.215.108:38081 1 1 auto
set -u
BASE="${1:-http://localhost:8080}"
CID="${2:-1}"
SID="${3:-1}"
MODE="${4:-auto}"

TASKS=(
  "先週の修繕と点検をまとめて報告書にして"
  "今週の利用状況をレポートにして"
  "未回答の質問を一覧にして"
  "経費精算の締め日を調べて"
  "経費精算の締め日を調べて、社内通知の下書きを作って"
  "今週の翻訳件数は？"
  "未対応の修繕は何件？"
  "点検タスクの状況を教えて"
  "未回答の質問から社内通知を作って"
  "先月の利用レポートを作って"
  "備品購入の申請フローを調べて通知文を作って"
  "修繕オーダーの一覧を Excel にして"
  "知識ベースにない質問を教えて"
  "今週の利用状況をまとめて、報告書にして"
  "何ができる？"
)

H=(-H "X-Customer-Id: $CID" -H "X-Store-Id: $SID")
RESULT="/tmp/agent-eval-result.jsonl"
: > "$RESULT"

echo "评测目标: $BASE  (customer=$CID store=$SID mode=$MODE)"
echo ""

i=0
for t in "${TASKS[@]}"; do
  i=$((i + 1))
  body=$(python3 -c 'import json,sys; print(json.dumps({"goal": sys.argv[1], "mode": sys.argv[2]}))' "$t" "$MODE")
  resp=$(curl -s -m 15 "${H[@]}" -H 'Content-Type: application/json' -d "$body" "$BASE/api/agent/tasks" 2>/dev/null)
  id=$(printf '%s' "$resp" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("id",""))
except Exception: print("")' 2>/dev/null)
  if [ -z "$id" ]; then
    echo "[$i] $t -> 提交失败: $resp"
    continue
  fi

  task=""
  for _ in $(seq 1 60); do
    task=$(curl -s -m 10 "${H[@]}" "$BASE/api/agent/tasks/$id" 2>/dev/null)
    st=$(printf '%s' "$task" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("status",""))
except Exception: print("")' 2>/dev/null)
    if [ "$st" = "done" ] || [ "$st" = "failed" ]; then break; fi
    sleep 2
  done

  printf '%s' "$task" | python3 -c '
import json, sys
t = json.load(sys.stdin)
steps = t.get("steps") or []
print(json.dumps({
    "id": t.get("id"), "goal": t.get("goal"), "status": t.get("status"),
    "has_artifact": bool(t.get("has_artifact")), "steps": len(steps),
    "retries": sum(1 for s in steps if (s.get("attempts") or 1) > 1),
    "error": t.get("error")
}, ensure_ascii=False))' >> "$RESULT" 2>/dev/null
  echo "[$i] $(tail -1 "$RESULT")"
done

echo ""
echo "=== 汇总 ==="
python3 - "$RESULT" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
n = len(rows)
if n == 0:
    print("无结果"); raise SystemExit
done = [r for r in rows if r["status"] == "done"]
art = [r for r in rows if r.get("has_artifact")]
steps = sum(r["steps"] for r in rows)
retries = sum(r["retries"] for r in rows)
print(f"任务数: {n}")
print(f"完成率: {len(done)}/{n} = {100*len(done)/n:.0f}%")
print(f"产物率: {len(art)}/{n} = {100*len(art)/n:.0f}%")
print(f"平均步数: {steps/n:.1f}")
print(f"重试步数占比: {retries}/{steps} = {100*retries/max(steps,1):.0f}%")
fails = [r for r in rows if r["status"] != "done"]
if fails:
    print("失败/未完成:")
    for r in fails:
        print(f"  - [{r['id']}] {str(r['goal'])[:40]} → {str(r.get('error') or r.get('status'))[:120]}")
PY
