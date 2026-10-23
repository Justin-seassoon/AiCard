#!/usr/bin/env bash
#
# status-audit.sh — 从源码 / 迁移派生「实际达成」，对照《两条腿-技术需求文档》声称的状态。
#
# 原理：文档不是真源，源码与 Flyway 迁移才是。本脚本把「文档声称的状态」编码成期望值，
#       对照代码现状；不一致（⚠️）就说明代码或文档有一方漂移，需更新基线文档。
# 用法：bash scripts/status-audit.sh
#
set -u
cd "$(dirname "$0")/.."

SRC=src/main/java/com/aicard
MIG=src/main/resources/db/migration
pass=0
warn=0

f()   { [ -f "$SRC/$1" ]; }          # 文件存在
d()   { [ -d "$SRC/$1" ]; }          # 目录存在
mig() { grep -rqi "$1" "$MIG"; }     # 迁移里出现某表名/关键字
pom() { grep -qi "$1" pom.xml; }     # pom 依赖

chk() { # <label> <expect: present|absent> <test-cmd...>
  local label="$1" expect="$2"; shift 2
  if "$@" >/dev/null 2>&1; then local actual=present; else local actual=absent; fi
  if [ "$actual" = "$expect" ]; then
    printf "  ✅ %-30s %s\n" "$label" "$actual"
    pass=$((pass+1))
  else
    printf "  ⚠️  %-30s 期望=%s 实际=%s  ← 漂移，请更新《技术需求文档》\n" "$label" "$expect" "$actual"
    warn=$((warn+1))
  fi
}

echo "=== 腿 A / 共享核心（文档声称 ✅ 已完成）==="
chk "设备接入网关"   present f gateway/handler/GatewayWebSocketHandler.java
chk "设备鉴权"       present f gateway/auth/DeviceAuthenticator.java
chk "翻译编排"       present f translation/orchestrate/TranslationOrchestrator.java
chk "LID 决策"       present f translation/decision/LidDecisionEngine.java
chk "质量门控"       present f translation/qualify/UtteranceQualifier.java
chk "SKB 受控 RAG"   present f skb/service/SkbService.java
chk "VKB 撞库"       present f translation/handler/TranslationHandlerImpl.java
chk "广播"           present f broadcast/service/BroadcastService.java
chk "运营通知"       present f notification/service/NotificationService.java
chk "维护/点检"      present f maintenance/service/MaintenanceService.java
chk "OTA"            present f ota/service/OtaService.java
chk "旁路采集"       present f ingestion/service/IngestionService.java
chk "供应商抽象(语音)" present f provider/api/SpeechProvider.java
chk "供应商抽象(LLM)"  present f skb/provider/LLMProvider.java

echo "=== 腿 B / 横向（①②③ 已实现；会议纪要云端 P0；C1/C2 待开发）==="
chk "① 自生长知识(skb/curation)" present d skb/curation
chk "① knowledge_gap 表"        present mig knowledge_gap
chk "③ 文档生成(poi/docx)"       present pom poi
chk "② agent 模块"              present d agent
chk "② agent_task 表"           present mig agent_task
chk "会议纪要模块(meeting)"      present d meeting
chk "会议表(meeting)"            present mig "CREATE TABLE meeting"
chk "会议后台接口"               present f meeting/MeetingAdminController.java
chk "会议 WAV 导入"              present f meeting/WavPcm.java
chk "C1 用户模型(app_user)"      absent mig app_user
chk "C2 鉴权(Spring Security)"   absent pom spring-boot-starter-security

echo "=== 数据库 schema（迁移 = 真源）==="
echo "  迁移文件数：$(ls "$MIG"/*.sql 2>/dev/null | wc -l | tr -d ' ')  最新：$(ls "$MIG" | sort -V | tail -1)"
echo "  JPA 一致性守卫：$(grep -A3 'jpa:' src/main/resources/application.yml | grep ddl-auto | tr -d ' ')"

echo
echo "───────────────────────────────────────────────"
echo "  一致 $pass 项 ｜ 漂移 $warn 项"
if [ "$warn" -gt 0 ]; then
  echo "  ⚠️ 有漂移：请对照两份基线文档更新状态。"
  exit 1
else
  echo "  ✅ 代码现状与《技术需求文档》声称的状态一致。"
fi
