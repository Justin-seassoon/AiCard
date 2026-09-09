# AI 工牌 1.0 · 云端后端（aicard）项目说明

# 梳理时间：2026/09/09

## 1. 项目简介

**aicard** 是「AI 工牌 1.0」的云端后端（Middleware），面向日本市场服务业的可佩戴式 AI 语音翻译终端（胸牌/工牌形态）提供云端能力。终端只做音频采集/播放/按键/墨水屏，语言判定（LID）、ASR/MT/TTS、供应商路由、知识库问答、设备管理等全部在云端完成。

> 权威需求来源：《AI工牌1.0产品需求文档（0804）》。架构 spec 见 `docs/superpowers/specs/2026-08-21-ai-card-software-architecture-design.md`。

### 核心功能

| 功能 | 说明 |
|------|------|
| **设备接入网关** | WSS 长连接 + 设备鉴权（device_id + token + firmware_version），二进制音频帧 + JSON 控制消息编解码，会话管理 |
| **翻译主链路** | 连续对话「边说边识别」流式：fixed 模式按 lang_pair 双向翻译；auto 模式一步 auto-detect（Azure 边听边判语言+翻译），目标时延 <1.5s |
| **质量门控** | 游客发言分类过滤（short/numeric/brand/whitelist），防止无效语音污染语言记忆 |
| **员工知识库（SKB）** | 受控 RAG：pgvector 检索 → LLM 受限生成 → 非 LLM 校验，杜绝编造 |
| **游客知识库（VKB）** | 游客提问自动应答：命中则「嘀嘀」提示服务人员，按 OK 确认后答案反向翻译成游客语言播放 |
| **旁路采集** | 翻译结果脱敏异步落库（translation_sample），形成数据飞轮 |
| **运营后台** | 设备管理、知识库文档导入/发布 REST + RBAC 骨架 |

---

## 2. 技术栈

| 类别 | 技术 | 版本/说明 |
|------|------|-----------|
| **语言** | Java | 17 |
| **框架** | Spring Boot | 3.3.5（模块化单体） |
| **Web** | spring-boot-starter-web / websocket | 8080 端口，WSS 设备接入 + REST 后台 |
| **持久层** | Spring Data JPA + JdbcTemplate | JPA 管租户实体，知识库走 JdbcTemplate |
| **数据库** | PostgreSQL + pgvector | pg16，向量扩展存 embedding |
| **迁移** | Flyway | V1–V6 |
| **语音供应商** | Azure AI Speech | client-sdk 1.51.2，region japaneast（LID/ASR/MT/TTS 一步） |
| **Embedding** | Azure OpenAI text-embedding-3-small | REST 调用，1536 维 |
| **LLM** | aibridgex（OpenAI 兼容网关）deepseek-v4-flash | REST 调用，受限生成 + 翻译 |
| **测试** | JUnit 5 + Mockito + AssertJ + Testcontainers | 88 用例（含 pgvector 集成） |
| **容器化** | Docker + Docker Compose | eclipse-temurin:17-jre 多阶段 |
| **构建** | Maven | spring-boot-maven-plugin |

---

## 3. 项目结构

```
aicard/
├── src/main/java/com/aicard/
│   ├── AiCardApplication.java           # Spring Boot 入口
│   ├── common/                          # P1 四层租户隔离骨架
│   │   ├── tenant/                      #   Tenant / TenantContext / TenantContextFilter
│   │   ├── domain/                      #   Customer / Store / Device
│   │   └── repository/                  #   Customer/Store/Device/DeviceScoped 仓储
│   ├── gateway/                         # P3 设备接入网关
│   │   ├── auth/                        #   握手鉴权（device_id + token）
│   │   ├── protocol/                    #   音频帧/消息编解码（AudioFrameCodec / InboundMessage / OutboundMessage）
│   │   ├── handler/                     #   GatewayTransportHandler / GatewayWebSocketHandler / TranslationHandler / SkbHandler
│   │   ├── session/                     #   SessionManager / SessionContext
│   │   └── state/                       #   DeviceStateTracker（在线/睡眠/离线）
│   ├── provider/                        # P2 供应商抽象
│   │   ├── api/                         #   SpeechProvider / LidResult / TurnSession / SpeechTranslationResult
│   │   ├── azure/                       #   AzureSpeechProvider（一步 auto-detect 流式）
│   │   ├── mock/                        #   MockSpeechProvider（可编程）
│   │   └── routing/                     #   ProviderRouter + ProviderMetrics（多供应商降级）
│   ├── translation/                     # P4 翻译主链路 + 质量门控
│   │   ├── decision/                    #   LidDecisionEngine / DecisionInput / DirectionDecision
│   │   ├── state/                       #   TranslationSessionState / PendingSwitch（软锁定记忆）
│   │   ├── orchestrate/                 #   TranslationOrchestrator / TranslationResult
│   │   ├── handler/                     #   TranslationHandlerImpl（流式「边说边识别」+ VKB）
│   │   ├── config/                      #   TranslationConfig（装配 provider/orchestrator/qualifier）
│   │   ├── qualify/                     #   UtteranceQualifier / UtteranceQuality（质量门控）
│   │   └── audio/                       #   BeepAudio（VKB 提示音）
│   ├── skb/                             # P5 员工/游客知识库（受控 RAG）
│   │   ├── service/                     #   SkbService（检索→生成→校验）
│   │   ├── store/                       #   KnowledgeStore（pgvector 检索）
│   │   ├── model/                       #   Document / Chunk / RetrievedChunk / SkbResult / LlmResult
│   │   ├── provider/                    #   EmbeddingProvider / LLMProvider（+ azureopenai / aibridgex / mock）
│   │   ├── seed/                        #   KnowledgeSeeder / MarkdownQaParser / KnowledgeEvalRunner
│   │   ├── handler/                     #   SkbHandlerImpl（员工问答 scope）
│   │   ├── web/                         #   SkbController（测试接口）
│   │   └── config/                      #   SkbConfig
│   ├── ingestion/                       # P6 旁路采集
│   │   ├── model/                       #   TranslationSample
│   │   └── service/                     #   IngestionService（脱敏异步落库）
│   └── admin/                           # P6 运营后台
│       ├── controller/                  #   DeviceController / DocumentController
│       ├── dto/                         #   DeviceDto / DocumentImportRequest
│       └── auth/                        #   AdminContextFilter / Role / AdminContext（RBAC 骨架）
├── src/main/resources/
│   ├── application.yml                  # 主配置（数据库/Azure/aibridgex/质量门控）
│   ├── static/index.html                # 测试页（SKB/VKB 切换）
│   └── db/migration/                    # Flyway V1__init ~ V6__document_domain
├── src/test/java/com/aicard/            # 单元/集成测试（与 main 镜像结构）
├── docs/                                # 需求/知识库/接口规范/联调文档
├── deploy-docs/                         # 运维部署说明
├── Dockerfile                           # eclipse-temurin:17-jre
└── docker-compose.yml                   # pgvector 数据库
```

---

## 4. 核心架构

### 4.1 系统架构（目标参考架构）

```
Device Badge（工牌）
  | 会话级短时流式音频通道（WSS，不要求长连接）
API Gateway / 设备接入层（鉴权：device_id + token + firmware_version）
  |
Session Manager + Device Manager（在线/睡眠/离线状态）
  |
Middleware Router
  |-- LID（在选定语言对内做语种判定）
  |-- ASR Provider: iFlytek / Volcengine / Google / Tencent
  |-- MT  Provider: Google / Tencent / Volcengine / iFlytek
  |-- TTS Provider: iFlytek / Google / Volcengine / Tencent
  |-- 可选 KB / 场景技能库（SKB 员工知识库 / VKB 游客知识库）
  |
TTS 音频回包 -> 设备扬声器外放
```

三大模块边界：**端侧固件**（连续对话状态机、VAD 断句、音频分片、TTS 播放、按键/墨水屏/LED、低功耗）、**云端 Middleware**（本仓库核心）、**H5/后台**（一机一码扫码配置、语言对、LID 策略、设备绑定）。

### 4.2 翻译主链路（流式）

- **fixed 模式**：跳过 LID，按 lang_pair 双向翻译（`startTurn` 一步 speech-to-speech，边收边译边流式回 TTS）。
- **auto 模式**：游客（counterparty）走 `startTurnAuto` 一步 auto-detect（Azure `AutoDetectSourceLanguageConfig`，边听边判语言+翻译）；员工（wearer）保持累积，依赖软锁定记忆 `visitor_language` 决定翻译方向。
- **质量门控**：游客发言先经 `UtteranceQualifier` 分类，仅 `NORMAL` 更新语言记忆，short/numeric/brand/whitelist 翻译照常但不污染记忆。

### 4.3 知识库受控 RAG（检索 → 生成 → 校验）

```
问题 → embedding → pgvector 余弦检索 topK=5（阈值 θ_retr）
      → LLM 受限生成（唯一一次调用，引用必须选自命中片段）
      → 非 LLM 校验（引用 ID ⊆ 命中集合 + 答案/片段向量相似度 ≥ θ_check）
      → ok（有依据回答）/ no_match（无依据，明确回「暂无明确说明」）
```

### 4.4 四层租户隔离

```
customer（客户/租户）
   └── store（门店）
          └── device_group（设备组，1.0 预留）
                 └── device（设备，绑定 staff_language）
```

- 所有业务表带 `customer_id`/`store_id`；仓储查询强制从 `TenantContext` 取租户过滤。
- 知识库绑定 customer + store，并加 `domain` 字段区分 `skb`（员工）/ `vkb`（游客）。

### 4.5 软锁定语言记忆

- `TranslationSessionState`：`visitor_language`（UNKNOWN 或具体语种）+ `pending`（待切换计数）。
- 已知局限（记录在 `known-issue-visitor-language-drift` memory）：一步 auto-detect 后游客方向不读记忆，记忆仅用于员工方向；换游客不重置、夹杂语言会漂移，联调后再评估防漂移。

---

## 5. 模块详解

### 5.1 common — 四层隔离骨架

| 类 | 说明 |
|----|------|
| `Tenant` / `TenantContext` | 租户上下文（ThreadLocal），`require()` 强制取当前租户 |
| `TenantContextFilter` | 请求/连接进入时写租户，退出时清理 |
| `Customer` / `Store` / `Device` | 三层实体（device 绑定 staff_language） |
| `DeviceScopedRepository` | 仓储基类，强制 tenant 过滤 |

### 5.2 gateway — 设备接入网关

| 类 | 说明 |
|----|------|
| `GatewayTransportHandler` | WSS 连接即会话，二进制/文本帧编解码收发、session_init 建会话、租户上下文写入清理、设备状态标记 |
| `GatewayWebSocketHandler` | 按帧类型分发，按 scope 路由到翻译/问答 handler |
| `GatewayHandshakeInterceptor` / `DeviceAuthenticator` | 握手鉴权（device_id + token） |
| `AudioFrameCodec` | 上行音频分片（0x01）/ 下行 TTS（0x82）二进制编解码 |
| `InboundMessage` / `OutboundMessage` | JSON 控制消息（eou/stop_tts/button_event/language_state/error…） |
| `SessionManager` / `SessionContext` | 会话上下文（scope/translationMode/langPair/turnId） |
| `DeviceStateTracker` | 设备在线/睡眠/离线状态 |

### 5.3 provider — 供应商抽象

| 类 | 说明 |
|----|------|
| `SpeechProvider` | 组合接口：detectLanguage / translateToSpeech / transcribe / synthesize / startTurn / startTurnAuto |
| `AzureSpeechProvider` | Azure 一步 auto-detect 流式（TranslationRecognizer synthesizing 边译边回 TTS） |
| `MockSpeechProvider` | 可编程 mock（测试/开发，无 key 时装配） |
| `ProviderRouter` / `ProviderMetrics` | 多供应商路由 + 指标（失败降级预留） |

### 5.4 translation — 翻译主链路 + 质量门控

| 类 | 说明 |
|----|------|
| `TranslationHandlerImpl` | 流式「边说边识别」：fixed/auto 分流、VKB 撞库、旁路采集 tee |
| `TranslationOrchestrator` | fixed 直译 / auto 决策（LID 前置 → 方向决策 → 一步流式翻译） |
| `LidDecisionEngine` | 软锁定决策（纯函数），wearer/counterparty/uncertain 分支 |
| `UtteranceQualifier` / `UtteranceQuality` | 质量门控：short/numeric/brand/whitelist 四分类过滤 |

### 5.5 skb — 受控 RAG 知识库

| 类 | 说明 |
|----|------|
| `SkbService` | 三段编排：检索 → 生成 → 校验，阈值 θ_retr/θ_check 注入 |
| `KnowledgeStore` | pgvector 余弦检索，强制 tenant + domain 过滤，仅 published 文档 |
| `EmbeddingProvider` | 文本向量化（azureopenai / mock） |
| `LLMProvider` | 受限生成 + 翻译（aibridgex / mock） |
| `KnowledgeSeeder` | Markdown 灌库工具（主题=document，问答=chunk） |
| `KnowledgeEvalRunner` | 内置 20 题日语问题，跑真实链路汇总命中率 |

### 5.6 ingestion / admin — 旁路采集 + 后台

| 类 | 说明 |
|----|------|
| `IngestionService` | 翻译样本脱敏后异步落库（绝不阻塞主链路，失败静默） |
| `DeviceController` | `/api/devices` 设备列表 + 创建（生成 token） |
| `DocumentController` | `/api/documents` 知识库文档导入（draft）+ 发布 |
| `AdminContextFilter` / `Role` | RBAC 骨架（admin 角色校验） |

---

## 6. 知识库

### 6.1 目录结构（docs 下）

```
docs/
├── 演示知识库-便利店与免税店-日语版.md     # SKB 内容（19 主题，308 条问答）
├── 演示知识库-连锁酒店-日语版.md            # SKB 内容
├── VKB游客FAQ-日语版.md                    # VKB 内容（20 document，38 条）
├── 知识库验收评估-*-日语版.md              # 内容验收评估报告
├── 知识库检索测试指南-日语.md               # 检索测试指南
└── 品牌词表-初稿.md                        # 质量门控品牌词表
```

### 6.2 知识文件格式

- 日语版「一问一答」Markdown，`## 主题` + `**Q：问题**` + `A：答案`。
- 由 `MarkdownQaParser` 解析：主题 = document，问答对 = chunk。
- chunk 文本为 `"Q: 问题 A: 答案"` 整段；embedding 向量用**问题**单独生成（避免答案稀释问题向量的相似度）。

### 6.3 向量库

- 用 pgvector（与业务库同库，免独立服务），`chunk.embedding vector(1536)`。
- 检索：余弦相似度 `1 - (embedding <=> ?)`，topK=5，门槛 θ_retr=0.55。
- 无独立集合分治（单一 domain 过滤），无关键词/hybrid 检索（当前为纯向量）。

---

## 7. 配置说明

### 7.1 环境变量（application.yml）

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL 连接 | `jdbc:postgresql://localhost:5432/aicard` / aicard / aicard |
| `AZURE_SPEECH_KEY` / `AZURE_SPEECH_REGION` | Azure Speech | 空（空则装配 Mock）/ japaneast |
| `AZURE_OPENAI_ENDPOINT` / `AZURE_OPENAI_KEY` | Azure OpenAI（embedding） | 空（空则 Mock） |
| `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` | embedding 部署名 | text-embedding-3-small |
| `AIBRIDGEX_BASE_URL` / `AIBRIDGEX_KEY` / `AIBRIDGEX_MODEL` | LLM 网关 | api.aibridgex.net / 空 / deepseek-v4-flash |
| `translation.qualify.*` | 质量门控（min-utt-duration-ms / numeric-filter / whitelist / brand-terms） | 见 application.yml |

### 7.2 质量门控配置

```yaml
translation:
  qualify:
    min-utt-duration-ms: 200        # 时长阈值，<0 关闭
    numeric-filter: true            # 纯数字过滤开关
    whitelist: OK,Yes,No,Thank you,...   # 短词白名单（精确匹配）
    brand-terms: Visa,Mastercard,...     # 品牌词表（包含匹配）
```

### 7.3 知识库阈值

| 参数 | 说明 | 默认 |
|------|------|------|
| `skb.theta-retr` | 检索门槛（top1 余弦相似度） | 0.55 |
| `skb.theta-check` | 校验门槛（答案/片段相似度） | 0.6 |

---

## 8. 本地开发

### 8.1 环境准备

- JDK 17（⚠️ 项目配置 Java 17；本机若 `JAVA_HOME` 指向 JDK 25，Lombok 注解处理器会失效导致编译失败，需 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home` 或等效）
- Maven 3.9+
- Docker（跑 pgvector / Testcontainers）

### 8.2 依赖安装与构建

```bash
cd /Users/jianghua/aiCard
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home mvn clean package -DskipTests
```

### 8.3 本地启动

```bash
# 启动数据库
docker compose up -d postgres

# 启动应用（无 AZURE_SPEECH_KEY 时装配 Mock 供应商）
JAVA_HOME=... mvn spring-boot:run
# 或
JAVA_HOME=... java -jar target/aicard-0.1.0-SNAPSHOT.jar
```

### 8.4 测试

```bash
JAVA_HOME=... mvn test              # 全量 88 用例（真实 Azure/Aibridgex SmokeTest 无 key 自动跳过）
JAVA_HOME=... mvn -Dtest=UtteranceQualifierTest test   # 单测
```

---

## 9. 部署方案

### 9.1 Docker 构建

```bash
# 构建 jar
mvn clean package -DskipTests
# 构建镜像（Dockerfile: eclipse-temurin:17-jre + COPY jar）
docker build -t aicard:latest .
```

### 9.2 容器清单（测试服务器）

| 容器名 | 镜像 | 端口 | 说明 |
|--------|------|------|------|
| `aicard-app` | `aicard:latest` | 8080 | 云端后端（WSS + REST） |
| `aicard-postgres` | `pgvector/pgvector:pg16` | 5432 | PostgreSQL + pgvector |

### 9.3 部署环境

- 测试服务器：内网 `10.0.189.32` / 公网 `120.253.215.110`，8080 端口已放通。
- 数据库：专用库 `db_ai_card`（早期 root 库已清空）。
- 密钥经容器环境变量注入（AZURE_SPEECH_KEY / AZURE_OPENAI_KEY / AIBRIDGEX_KEY），不落盘。
- 测试页：`http://120.253.215.110:8080/`（支持 SKB/VKB 切换）。

### 9.4 运行命令

```bash
docker run -d --name aicard-app --restart always \
  -p 8080:8080 \
  -e DB_URL=... -e DB_USER=... -e DB_PASSWORD=... \
  -e AZURE_SPEECH_KEY=... -e AZURE_SPEECH_REGION=japaneast \
  -e AZURE_OPENAI_KEY=... -e AIBRIDGEX_KEY=... \
  aicard:latest
```

---

## 10. API 接口

### 10.1 WSS 设备接入（网关）

- 握手鉴权：`device_id` + `token`（Header/参数）。
- 上行 JSON 控制消息 `InboundMessage`：`session_init | eou | stop_tts | sleep_notice | button_event | playback_event | scope_change | translation_mode_change | headset_event | config_pull`。
- 上行二进制音频帧（0x01）：`turnId / sequence / timestamp / sourceSide / inputSource / audio`。
- 下行 JSON `OutboundMessage`：`tts_end / language_state / error / config_update`。
- 下行二进制 TTS 帧（0x82）：`turnId + 音频`（≤8KB 分片，下行带 44 字节 WAV 头）。

### 10.2 REST 后台

| 路由 | 方法 | 说明 |
|------|------|------|
| `/api/devices` | GET | 本租户设备列表 |
| `/api/devices` | POST | 创建设备（绑定租户、生成 token） |
| `/api/documents` | POST | 导入知识库文档（draft） |
| `/api/documents/{id}/publish` | POST | 发布文档 |

### 10.3 REST 测试接口

| 路由 | 方法 | 说明 |
|------|------|------|
| `/api/skb/answer` | GET | 知识库问答（question / customerId / storeId / domain） |
| `/api/skb/documents` | GET | 返回知识库全部内容（按主题分组） |
| `/actuator/health` | GET | 健康检查 |

---

## 11. 数据库

### 11.1 PostgreSQL + pgvector

| 表 | 说明 |
|----|------|
| `customer` | 客户/租户（code/name） |
| `store` | 门店（customer_id 外键） |
| `device` | 设备（device_id/token/staff_language，绑定 customer+store） |
| `document` | 知识库文档（title/version/status/domain，SKB/VKB 隔离） |
| `chunk` | 文档切片（text + embedding vector(1536)） |
| `translation_sample` | 旁路采集翻译样本（脱敏，数据飞轮） |

- 迁移由 Flyway 管理（V1–V6），`ddl-auto=validate`。
- 关键索引：device/store/document/chunk 均有 customer_id+store_id 复合索引，document 有 domain 索引。

---

## 12. 运维手册

### 12.1 知识库更新（灌库）

```bash
# 灌库（非幂等，重复执行会重复插入；正式演示前先清 document/chunk 表）
java -jar app.jar \
  --skb.seed.enabled=true \
  --skb.seed.files=/app/便利店与免税店-日语版.md,/app/连锁酒店-日语版.md \
  --skb.seed.customer-id=1 --skb.seed.store-id=1
```

### 12.2 检索质量评估

```bash
java -jar app.jar --skb.eval.enabled=true --skb.eval.customer-id=1 --skb.eval.store-id=1
# 输出 20 题命中率：命中(ok)=X / 未命中(no_match)=Y
```

### 12.3 常用运维命令

```bash
docker ps --filter name=aicard-app
docker logs -f aicard-app
docker restart aicard-app
curl http://localhost:8080/actuator/health
docker logs aicard-app 2>&1 | grep -E "Flyway|Started AiCardApplication"
```

---

## 附：已知局限与遗留事项

- **员工方向语言漂移**：换游客不重置记忆、夹杂语言时员工方向会漂移（已记录，联调后再评估防漂移）。
- **button_event(ok) 端侧实现**：VKB 真机联调前提（服务人员按 OK 键动作）。
- **耳机输出路由**：VKB 提示音走耳机仍后置，当前走扬声器。
- **品牌词表/白名单后台可配**：当前仅静态配置，计划下周实现后台管理 + OTA。
