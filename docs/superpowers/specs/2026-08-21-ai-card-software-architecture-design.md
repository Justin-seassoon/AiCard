# AI 工牌 1.0 · 软件端顶层架构总纲（设计文档）

- **状态**：brainstorming 渐进产出，本文档收录「已对齐」章节；文末列出待讨论章节。
- **权威需求**：《AI工牌1.0产品需求文档（0804）》+《AI工牌1.0软件需求及功能边界》。
  - ⚠️ 目录内《技术架构·卷一~卷六》《技术补充需求与确认事项》为 **R818 时代旧文档**，与 0804 存在根本冲突（主控 R818→ESP32 三芯片、双麦→四麦、墨水屏→彩屏、长按问答→独立问答键、单知识库→双知识库等），已确认**全部以 0804 为准**，旧文档仅提取通用理念（能力抽象、多供应商路由、TTS 打断本地化、安全隔离思路）。
- **技术栈**：Java + Spring Boot（模块化单体起步）。

---

## 0. 已定决策快照

| 决策点 | 结论 |
|---|---|
| 权威需求 | 0804 PRD + 《软件需求及功能边界》 |
| 技术栈 | Java + Spring Boot（模块化单体起步） |
| 市场 / 部署 | 日本（与供应商同 region：Azure 日本东 / GCP 东京） |
| 翻译链路形态 | **云端薄代理**：设备 → 自研薄层（LID 独立前置 → ASR/MT/TTS）→ Google / Azure |
| 知识库链路 | 自研后台：RAG + ASR + LLM + TTS |
| 旁路采集 | 薄层 tee 翻译文本（脱敏）；原始音频 demo 默认关闭，合规审批后开启 |
| 供应商 | Google + 微软(Azure) 双家，多供应商抽象 + 失败降级 |
| 游客知识库 vkb | **显式范围裁剪（1.0 后置，已批准）**，留 `query_scope` 隔离 + `final_text` 旁路复用两个钩子 |
| 员工知识库 skb | 1.0 P0，受控 RAG（这是「RAG+LLM」的价值重心） |

### 范围偏离记录（已批准）

| 偏离项 | PRD 原要求 | 本方案处理 | 受影响条款 | 状态 |
|---|---|---|---|---|
| 游客知识库 vkb（SW-VKB 全 9 项 P0） | 0804 §4.1 P0「翻译 + 游客知识库并行匹配、员工 OK 确认后播放」 | **1.0 后置**，留 `query_scope` 隔离 + `final_text` 旁路复用两钩子 | §0.2「员工确认」哲学、§1.2「服务标准化」目标（1.0 暂无法落地） | 已批准（对话决策）；PRD scope 需同步更新 |

> 此裁剪已获甲方确认，需同步更新 0804 PRD / 软件需求边界 scope，并在正式交付前留书面批准记录，避免 ODM 按 PRD 联调时云端缺失 vkb 造成阻塞。

---

## 1. 系统全景与部署拓扑

### 1.1 四条流量

```
                     ┌─────────────────────────────────────────────┐
 设备(ODM端侧)        │   自研后端 (Java · Spring Boot · 日本)       │
                     │                                             │
 ①翻译语音 ──WSS──►  │  gateway ──► translation-proxy(薄) ──流式──► │ Google/Azure
   ◄─TTS流──WSS──    │        ▲            │tee旁路                    │ (ASR/MT/TTS)
                     │        │            ▼                          │
 ②知识库语音─WSS──►  │  gateway ──► skb ──► RAG(向量+LLM)+ASR/TTS     │
   ◄─答案TTS─WSS──   │                                             │
 ③旁路样本 ──异步──► │  ingestion ──► 样本存储(脱敏)                 │
 ④配置/状态─WSS──    │  admin(后台API) ◄── H5/运营后台(前端SPA)      │
                     └─────────────────────────────────────────────┘
```

1. **① 翻译主链路**（P0，最高优先）：设备 → 薄代理 → 供应商 → 薄代理 → 设备。薄代理只做「鉴权 + LID 路由 + Key 管理 + 流式透传 + 降级」，**不做重业务**。
2. **② 知识库链路**（P0）：设备 → 知识库模块 →（ASR→检索→受控 RAG→LLM→TTS）→ 设备。自研重心。
3. **③ 旁路采集**（P1）：翻译结果异步 tee 回传，走独立 `ingestion`，**绝不阻塞①**。demo 默认只存脱敏文本（源文/译文/语言对/质量元数据）；原始音频回传默认关闭，须经 0804 §14 合规审批后开启。
4. **④ 控制面**：配置下发（WSS `config_update`/pull，见 §11）+ H5/运营后台（独立 SPA，走 `admin` REST）。

### 1.2 部署拓扑

- 单应用**模块化单体**起步，容器化（Docker），部署在**日本**（与供应商同 region，保证薄代理「多一跳」≈ 1–5ms）。
- `translation-proxy` 与知识库在**代码模块层彻底分开**，未来翻译需独立扩缩容时，可单独部署该模块副本，不改代码结构。
- 供应商 Key 只存服务端 Secret 管理（Vault / 云 KMS / 托管密钥），**永不下发设备**（0804 §8.7 硬约束）。

---

## 2. 模块划分与边界

| 模块 | 职责 | 依赖 |
|---|---|---|
| `gateway` | WSS 接入、鉴权(`device_id`+`token`+`fw`)、会话生命周期、设备状态、心跳/`sleep_notice` | — |
| `translation-proxy` | LID 软锁定策略、供应商抽象与流式转发、TTS 回放、Key 管理、降级、成本计量 | gateway |
| `vkb` 游客知识库 | 【1.0 后置】并行匹配 + 员工 OK 确认候选 | gateway |
| `skb` 员工知识库 | 受控 RAG：向量检索 + LLM 受限生成 + `source_refs` 追溯 | gateway |
| `ingestion` | 旁路样本接收、脱敏、落库（数据飞轮） | — |
| `admin` | 后台 REST API：设备/语言/知识/权限/日志/成本 | gateway |
| **横切** | `provider-abstraction`(Google/Azure 适配器)、`isolation`(四层隔离)、`observability`(脱敏日志+成本) | 全模块 |

**关键边界（写死）：**

- 双知识库通过 `query_scope`(customer/staff) + `customer_id` + `store_id` + 权限**四层强隔离**，索引/数据/权限/接口层各自独立。
- `translation-proxy` 是**薄**的：业务逻辑只到「LID 路由 + 降级」为止，不承载知识库、不承载会话状态记忆的复杂编排（记忆在 `gateway` 会话上下文）。
- 供应商能力统一走 `provider-abstraction`，Google 与 Azure 是首批两个适配器，新增供应商 = 新增适配器，不改上层。

---

## 3. 三条链路数据流

### 3.1 ① 翻译主链路（薄代理，P0）

```
设备：短按翻译键 → WSS 建连（鉴权 device_id+token+fw）
        └─ 音频分片流式上传 [turn_id / sequence / timestamp / audio_payload]
        └─ VAD 静音~1.2s 触发 end_of_utterance 尾包（含 source_side）
薄层：① 说话方判定（source_side：wearer / counterparty）
       ② 方向决策（AUTO：counterparty 走 LID 独立前置 + 软锁定；wearer 走反向翻译）
       ③ transcribe → translate → synthesize（固定语言对可走 Azure 一步）
       ④ 流式回 tts_audio（首帧即回，不等整段）
       ⑤ tee 旁路 → ingestion（finalText+translatedText+语言对+质量元数据）
       ⑥ 降级：低置信 → 不下发高风险译文，回「请再说一遍」/ 白名单直通（LOCKED）
设备：播 TTS；再次短按 / 空闲超时（阈值待配置，默认 20s）→ 断连 → 低功耗
```

### 3.2 ② 知识库链路（skb，P0）

```
设备：短按问答键 → WSS 建连（scope=staff_qa）或会话内 scope_change；query_scope=staff 由 scope 派生
        └─ 员工 staff_language 提问 → 音频分片上传
自研：① ASR（staff_language）
       ② 跨语种标准化（staff_language → kb_base_language）
       ③ 向量检索（当前客户/门店授权资料，四层隔离）
       ④ 受控 RAG：LLM 受限生成 + source_refs 追溯
       ⑤ 无依据 → 明确返回「暂无明确说明」，不编造
       ⑥ TTS（staff_language）
设备：播 TTS（耳机优先/扬声器）；连续问答，直到退出或超时
```

### 3.3 ③ 旁路采集 + 控制面

```
旁路：  薄层 tee → ingestion 端点 → 脱敏 → 落库（数据飞轮，异步，不阻塞①）
控制面：配置下发（WSS config_update/pull）→ lang_pairs / LID策略 / 白名单 / 音量 / endpoint
        后台：H5 SPA → admin REST → 设备/语言/知识/权限/日志/成本
```

---

## 4. 端云 WSS 接口契约（框架级）

> ODM 联调的前置；本小节只定**消息类型 + 关键字段**。采样率/编码/帧长/心跳间隔/错误码表/重连时序等，留待《端云通信接口规范》单独冻结（0804 §10.1）。

| 方向 | 消息/字段 | 说明 |
|---|---|---|
| 上行 | `device_id` + `token` + `fw_version` | 鉴权 |
| 上行 | `session_id` / `scope`(translate\|staff_qa) / `translation_mode`(auto\|fixed) | 会话 + 业务域 + 语言对模式 |
| 上行 | `turn_id` / `sequence` / `timestamp` / `audio_payload` | 轮次标识 + turn 内分片 |
| 上行 | `end_of_utterance` | VAD 尾包 |
| 上行 | `staff_language` / `lang_pair` / `headset_state` / `input_source` / `source_side` | 员工语言 + 语言对 + 耳机/来源 |
| 上行 | `sleep_notice` / `wifi_band` / `network_state` | 睡眠上报 + 网络状态 |
| 上行 | `stop_tts` / `button_event` / `playback_event` | 打断 / 按键（OK 确认）/ 播放完成 |
| 下行 | `final_text` / `partial_text` | 文本（**端侧不显示，调试/日志可选**） |
| 下行 | `tts_audio` | TTS 流，端侧外放 |
| 下行 | `answer` | 知识库答案（skb） |
| 下行 | `language_state` | 语言状态回传（visitor_language/lid_language/confidence/state） |
| 下行 | `error` / `config_update` | 错误码 / 配置下发 |
| 下行 | `output_route` / `fallback_action` | 路由（speaker/headset/pause）/ 降级动作（ask_repeat 等） |

**关键时序**

- **翻译端到端（PRD §12.1：目标 ≤2s、理想 ≤1.5s，TARGET 级）**：
  - 尾包口径：VAD 尾包 → 薄层说话方判定 + LID(~50-100ms) → 供应商 ASR→MT→TTS(600-1200ms，大头) → 薄层流式回 TTS 首帧 → 设备播放。
  - 体感口径：上值 + ~1.2s VAD 静音等待（用户说完话到发尾包），真实体感约 2.5–3.5s；此 1.2s 属端侧 VAD 产品取舍，验收口径须显式声明。
  - Google 三步串行中 TTS 首帧须等 MT 完整文本，是时延瓶颈，预算取保守值。
  - LID 独立前置使链路 LID→ASR→MT→TTS **严格串行**：ASR 须等 VAD 尾包 + LID 完成才启动，端侧流式分片上传的意义退化为「省尾包后传输延迟」，ASR 结构性非流式。此影响已计入上述预算，writing-plans 阶段勿误以为 ASR 可流式并行。
- **TTS 打断（端侧本地 ≤100ms，不等云端）**：见第 6 节。

---

## 5. 供应商能力抽象（provider-abstraction）

统一接口（底层能力，**LID 独立前置**）：

```
detectLanguage(audio, candidates) → { lid_lang, lid_confidence }   // LID：独立前置
transcribe(audio, lang)            → { finalText }                 // ASR
translate(text, src, tgt)          → { translatedText }            // MT
synthesize(text, lang)             → { ttsAudio }                  // TTS
```

**关键变更（P0-③ 修复）**：软锁定依赖 per-utterance 的 `lid_lang + lid_confidence`，而「一步 speech-to-speech」（Azure 黑盒）不返回置信度、Google 也不输出 per-language confidence，故 **LID 必须独立前置**，不能再走「语音进语音出」一步。

- **Azure 适配器**：LID 用 Azure Language Identification 能力；ASR/MT/TTS 用 Azure Speech + Translator + TTS。固定语言对模式下（无需 LID）可复用 Speech Translation speech-to-speech 一步，省时延。
- **Google 适配器**：LID 用 Google STT 语言检测（或专门 LID）；ASR/MT/TTS 用 STT + Cloud Translation + TTS 三步。
- **前置验证项**：两家 LID 的「per-utterance 语言置信度」是否可得、是否覆盖候选语种（ja/zh/en/ko 等），须选型阶段实测；若某家 LID 无置信度，软锁定退化为「信任单次语言判断」，需重新评估 §7 状态机。

> ⚠️ LID 独立前置使翻译链路多一次调用及其时延，见 §4 时延预算。

---

## 6. TTS 打断实现路径（含端侧逻辑）

### 6.1 两种打断

| 类型 | 触发 | 停播后做什么 | 0804 依据 |
|---|---|---|---|
| **A 语音抢占（barge-in）** | VAD 检测到新有效人声 | **立刻转入新一轮拾音**，继续翻译 | §8.3「新有效语音…停止」 |
| **B 按键停止** | 短按翻译键 / 问答键 | 翻译键=**结束会话**；问答键=停止播放/取消问答 | §6.2 按键定义 |

两者**停播的端侧机制完全相同**，区别只在停播后的状态去向。

### 6.2 端侧状态机（P4 主导，IA8201 提供 VAD/AEC）

```
        ┌────────────────────────────────────────────┐
        │              LISTENING（拾音中）             │
        │   IA8201采集 → VAD → P4缓存分片 → 流式上传    │
        └───────────────┬────────────────────────────┘
                        │ VAD 静音~1.2s → 发 end_of_utterance 尾包
                        ▼
        ┌────────────────────────────────────────────┐
        │            PROCESSING（等云端）              │
        │   LID→ASR→MT→TTS（云端薄层在做）              │
        └───────────────┬────────────────────────────┘
                        │ 收到首个 tts_audio 分片
                        ▼
        ┌────────────────────────────────────────────┐
        │              PLAYING（播放中）               │
        │   P4播放队列 → 功放/扬声器                     │
        │   同时：IA8201 持续采集 + 数字AEC（见6.3）      │
        └───────────────┬────────────────────────────┘
             新语音(VAD) │ 或 按键
                        ▼
                 [打断执行四步]  ← 6.4
                        │
         ┌──────────────┴──────────────┐
         │  A语音抢占 → 回 LISTENING     │  B按键 → 结束会话/停止问答
         └─────────────────────────────┘
```

### 6.3 关键前提：播放中持续采集 + 数字 AEC

语音抢占（A）成立的前提是 **PLAYING 时麦克风仍在监听**。但「播放中监听」有回采风险——扬声器外放被自己麦克风采回，VAD 会把「设备自己的 TTS」误判成「新语音」→ 无限自我打断。

解法是 0804 §8.2 已定的 **IA8201 数字参考 AEC**：

```
P4 播放队列 ──┬──► 功放 → 扬声器（外放给游客）
              │
              └──► IA8201 AEC 参考通路（实时喂给 DSP 做回声消除参考）
                            │
   IA8201 采集 ←─ 四麦 ─────┘  （消除扬声器回采后，残留 = 真人声）
                            │
                    VAD（只对「AEC 后残留」判起音）
```

⚠️ **P4 ↔ IA8201 之间必须有一条「播放参考通路」**（播放中的音频实时作为 AEC 参考）。没有这条，语音抢占打断做不了。这是端侧逻辑里最易漏、又最要命的一条。

### 6.4 打断执行四步（目标 ≤100ms，端侧本地闭环）

触发后 P4 **不等云端**，本地顺序执行：

```
① 停播放器      功放静音 / 停止播放句柄                 ~1-5ms
② 清缓存        丢弃播放队列 + 已收未播的 TTS 分片       ~0
③ 复位输出      复位 DAC/功放状态，准备下一轮             ~1-5ms
④ 恢复采集      切回 LISTENING，AEC 参考停止             ~0
                ──────────────────────────────────
                合计 ≤100ms（纯端侧，断网照样执行）
```

四步完成后**异步**补一步通知（不阻塞前四步）：

```
⑤ 异步上报     发 stop_tts{turn_id, reason=voice|button}
                → 云端停合成/释放/记账
                （仅停止 TTS 下行流，严禁断开 WSS 会话，见 6.5）
```

### 6.5 时序 + in-flight 竞态

```
端侧 PLAYING：  ████ 已播 ████ 缓冲中 ████（网络还在路上）
                     │   VAD 命中新语音 / 按键
                     ▼
            ①停播 ②清缓存 ③复位 ④切LISTENING        （≤100ms）
                     │
                     ├─ 迟到的 TTS 分片仍在抵达 ──► 按 turn_id 校验丢弃
                     │
                     └─ 新语音开始新一轮分片上传（新 turn_id，sequence 重新起）
云端：     仍在流式下 TTS ──► 收到 stop_tts / 新 turn_id 音频 ──► 停合成+释放+记账
                                                     （保留 session 与语言记忆）
```

**竞态规则：**

- 打断与新语音用 **turn_id（轮次标识）** 区分：上行音频、下行 TTS、`stop_tts` 都带同一 turn_id；`sequence` 仅作 turn 内分片序号，不跨轮次复用。
- 端侧清缓存后，**迟到的 TTS 分片必须丢弃**，判据是「分片所属 turn_id 与当前轮次不符」。
- 云端**容忍**「端侧在 TTS 下发中途停流」——这是正常打断，不是异常，不得记错误、不得回滚语言状态。

### 6.6 云端对应逻辑（薄层）

1. **停旧 TTS 的触发**：收到 `stop_tts` **或**同一 session 内收到新 turn_id 音频分片（新语音先于 stop_tts 到达）→ 停止向供应商要后续 TTS（Azure 停读流 / Google 停 TTS 调用）。
2. **释放本轮上下文，但保留会话 + `visitor_language` 记忆**（打断 ≠ 会话结束）。
3. **记账**：本次 TTS 标记「中断」，成本按已合成部分计，避免按整条重复计费。
4. 例外场景才用下行 `interrupt_tts`：云端主动终止会话（后台强制下线 / 设备解绑 / 账号吊销）时让端侧停——低频辅助，与 §10.2 `E_SESSION` 同一语义；不动「日常打断端侧本地」结论。

### 6.7 甲方要在接口规范写死的三条

1. **端侧打断四步 ≤100ms、断网可用**（产品级验收）。
2. **P4↔IA8201 必须有 AEC 播放参考通路**（语音抢占的前提）。
3. **迟到的 TTS 分片按 `turn_id` 丢弃、云端容忍中途断流不记错**。

---

## 7. LID 软锁定状态机

### 7.1 LID 独立前置（P0-③ 修复）

LID 是**独立的显式调用**（`detectLanguage`），先于方向决策与翻译，返回 `lid_lang + lid_confidence`；不再依赖「一步 speech-to-speech」黑盒（拿不到置信度）。

| 层 | 做什么 | 归谁 |
|---|---|---|
| 底层·语言识别 | 音频 → `lid_lang` + `lid_confidence` | 供应商 LID 能力（Azure Language Identification / Google STT 语言检测），独立前置调用 |
| 上层·软锁定策略 | visitor_language 迁移、降级 | 薄层（自研） |

供应商只给「这句话是什么语言 + 置信度」；软锁定、连续两次、白名单、品牌名过滤这套业务策略必须自研。

### 7.2 说话方决定方向，LID 决定记忆（关键分离）

- **翻译方向由 `source_side` 决定**（P0-② 修复）：`wearer`（员工）→ src=staff_language、tgt=visitor_language；`counterparty`（游客）→ LID 判 src、tgt=staff_language。
- **visitor_language（会话记忆）只由 counterparty 发言的 LID 结果更新**，wearer 发言不更新。

场景：`visitor_language=中文` 锁定时，游客突然冒一句完整英语 → **这一句按英语翻译**（LID 判它是英语），但**不立刻把会话记忆切到英语**（要连续两次才更新）；员工用 staff_language 回答则**反向翻译给游客**，不参与 LID。

### 7.3 状态机

```
visitor_language:  UNKNOWN ──(首个合格完整句)──► LOCKED(L)
                        ▲                          │
                        │              (连续两次 / 单次超高置信)
                        │                          ▼
                        └────────────── LOCKED(X) ◄─┘
                        （会话结束/超时/模式切换/重启 → 清空回 UNKNOWN）
                         （员工问答不清空）

辅助变量: pending_switch = { lang, count }   // 软锁定更新的"计数器"
```

### 7.4 每句有效语音决策流程（AUTO 模式）

```
输入：音频 + source_side(input_source 推导) + 候选语种集 + staff_language + visitor_language

① 说话方判定（source_side 强先验，非绝对结论）

   source_side 是强先验（决定默认方向），但需 LID 后验校验纠正（PRD §9.5）：

   [wearer 员工发言]
     visitor_language = LOCKED(L) → src=staff_language、tgt=L，不参与 LID 记忆
     visitor_language = UNKNOWN    → 无目标语言，降级：提示「请先让游客说话」/ 切固定语言对
     后验：LID==staff_language → 一致，继续
           LID≠staff_language 且高置信 → 疑似串音/误判 → E_CONFLICT

   [counterparty 游客发言]
     → LID 判定，走②③④⑤

   [uncertain / 两路冲突]
     → E_CONFLICT（不强行判断，提示依次说话）

② 质量门控（qualify，counterparty 发言参与记忆）
   语音时长 < min_utt_duration_ms → short，不参与记忆
   纯数字/符号                    → numeric，不参与
   品牌名/商品名/型号词表命中      → brand，不参与
   短词白名单命中                 → whitelist（见⑤）

③ LID（候选语种集内，独立前置）→ lid_lang + lid_confidence

④ 方向 + 记忆分支（counterparty 发言）

   [同语种] lid_lang == staff_language
     → 不锁定 visitor_language；双方同语种，可不翻译 / 保持当前语言 / 提示重说

   [UNKNOWN]
     完整句 && conf≥θ && lid_lang≠staff_language → LOCKED(lid_lang)；src=lid_lang、tgt=staff_language
     否则 → 保持 UNKNOWN，降级（提示重说 / 记日志）

   [LOCKED(L)]
     lid_lang == L                    → 按 L 翻译（软锁定保持）
     lid_lang == X≠L（完整句+高置信）→ pending 累加（见 7.5），本句仍按 X 翻译
     short/numeric/brand/whitelist/低置信 → 按 L 兜底翻译（不漂移、不更新）

⑤ 降级分支（counterparty 发言，置信度不足）
   conf ≥ θ                → 正常方向翻译 + 外放
   conf < θ 且白名单命中（LOCKED 状态）→ 直通翻译（白名单短句，如 OK/ありがとう/はい）
   conf < θ 且非白名单      → 不外放高风险译文：提示「请再说一遍」+ 记低置信日志
```

**修正点（对应 review）**：① 说话方判定用 source_side 而非 LID 语言（P0-②）；同语种单列分支（P1）；白名单直通仅 LOCKED 状态（UNKNOWN 不直通）；删除「按门店默认方向」（违背不确定不猜测）。

### 7.5 软锁定更新条件（已对齐）

`LOCKED(L)` 时遇到一句高置信新语言 `X`：

```
pending_switch.lang==X ? count++ : pending_switch={X, count=1}
   ├─ count ≥ 2（连续两句都是 X）        → 更新 LOCKED(X)，清 pending
   ├─ conf ≥ θ_super（单次超高置信）      → 立即更新 LOCKED(X)，清 pending
   └─ 否则                              → 保持 LOCKED(L)，pending 继续挂着
         （中间夹一句 L 或其他 → pending 重置）
```

**已确认两点：**

- **点 A**：「连续两次」= 连续两句**完整高置信**新语言，中间夹短词/数字/品牌名**不算打断**（它们不参与判定）。
- **点 B**：pending 里第一句「疑似 X」，翻译方向**按 X**（单句方向由 LID 决定），只是**不动会话记忆**——直到第二句确认才切换 `visitor_language`。

### 7.6 可配置参数（后台可配，0804 §11）

| 参数 | 初始建议值 | 说明 |
|---|---|---|
| LID 置信度阈值 θ | 0.8 | 0804 明确「由供应商口径校准，不固定」，0.8 仅起步 |
| 超高置信 θ_super | 0.95 | 单次即切换的阈值，联调校准 |
| 最短有效语音时长 min_utt_duration_ms | 待定 | 与 VAD 尾包关联，联调定 |
| 连续次数 N | 2 | 软锁定更新所需连续句数 |
| 短词白名单 | OK/Yes/No/谢谢/はい/ありがとう… | 后台可配 + OTA，命中直通 |
| 品牌名/型号词表 | 门店自配 | 命中不触发漂移 |
| 数字过滤 | 开 | 纯数字不参与 LID |

### 7.7 固定语言对模式（P0，稳定性兜底）

与 AUTO 并存的兜底模式：跳过 LID，直接按选定 lang_pair 双向翻译。

- 方向：游客发言 → src=lang_pair 游客侧、tgt=lang_pair 员工侧；员工发言 → 反向。
- 不建立 / 不更新 visitor_language（无 LID 记忆）。
- 供应商调用可走 Azure speech-to-speech 一步（无需 LID，省时延），或 Google 三步。
- 切换：AUTO ↔ 固定语言对由员工操作，切换时清空 visitor_language（与 §7.3 一致）。

---

## 8. 四层隔离模型

### 8.1 租户层级（隔离骨架）

```
customer（客户/租户）
   └── store（门店）
          └── device_group（设备组，1.0 预留）
                 └── device（设备，绑定 staff_language 等配置）
```

知识库绑定到 **customer + store** 两级；设备绑定到 store。1.0 落地以 **customer + store** 为主，`device_group` 留结构不强制。

### 8.2 核心原则（模型命门）

> **tenant 上下文从「设备身份 / 登录用户」推导，绝不信任请求参数里的 `customer_id` / `store_id`。**

- 设备请求：`device_id + token` 鉴权 → 查设备绑定关系 → **服务端推导** customer_id/store_id。
- 后台请求：登录用户 → RBAC → 推导可访问的 customer/store 范围。

`query_scope`/`customer_id`/`store_id` 必须来自**可信上下文**，请求体里带的只能做**校验**、不能做**依据**（否则换个 ID 即跨租户读取）。

### 8.3 四层落地

| 层 | 落地方式 |
|---|---|
| 接口层 | 每个 API 强制注入 tenant 上下文；来自设备鉴权结果或 RBAC 结果，与请求参数比对，不一致即拒绝 |
| 权限层 | RBAC：`platform_admin`(甲方运营) / `customer_admin`(客户) / `store_admin`(门店)；员工细分角色 2.0 扩展 |
| 数据层 | 所有业务表带 `customer_id`+`store_id` 列，仓储层**强制 filter**；双知识库**物理分表**（游客 KB / 员工 KB） |
| 索引层 | 向量/检索索引按 `customer_id`+`store_id`+`query_scope` 分区，检索时强制带 tenant filter |

### 8.4 query_scope 双知识库隔离

**1.0 只落地 `skb`（员工知识库）**——这是唯一实际在用的知识库。

| | 员工知识库 `staff` | 游客知识库 `customer` |
|---|---|---|
| 1.0 状态 | **P0，落地** | **后置** |
| 检索范围 | 当前 customer/store 授权资料 | （待 vkb 场景/流程确定后再设计） |
| 隔离规则 | 不得进入游客自动候选链路 | 不得调用员工资料（预留） |
| 数据形态 | SOP/手册/流程（文档 + RAG） | 审核后标准答案（预留） |

- vkb 只在 `query_scope` 枚举里**预留 `customer` 值 + 隔离框架钩子**，不设计其检索范围/隔离规则，等 vkb 定场景时再补。
- 双知识库**数据、索引、权限、接口**四层各自独立，物理不共享。

### 8.5 物理隔离强度

- **第一阶段（demo/展示）**：选 **A 共享库 + 行级隔离**（tenant 列 + 仓储层强制 filter）。物理隔离强度不是当前重点。
- **真实 B 端客户上线 → 私有化改造**：架构保留私有化钩子——`provider-abstraction`（供应商可切换）+ 部署形态可配置，改造时不动核心代码。

### 8.6 向量库选型（pgvector）

按「时延最短 + 稳定 + 私有化友好」三个标准定 **pgvector**：

| 维度 | pgvector | Milvus |
|---|---|---|
| 时延 | 同库检索，**无跨服务网络往返**，小规模 HNSW 毫秒级 | 多一次 RPC 往返，小规模不占优 |
| 稳定 | PostgreSQL 单点，故障面最小 | 依赖 etcd/MinIO/Pulsar 多组件，故障面大 |
| 私有化 | 单机交付 | 整套集群 |

补充：受控 RAG 的**端到端时延大头在 LLM 生成（秒级），不在向量检索（毫秒级）**，故向量库对整体时延影响本就小；「同库无往返」是绝对最短路径。RAG 与主库共用一个 PostgreSQL，不额外养向量服务。

---

## 9. 受控 RAG 边界（员工知识库 skb）

> 对应 0804 §7.3「不得脱离资料编造」「无依据明确拒答」。旧架构卷三的「五道关卡」中「范围判断」与「向量匹配」实为同一动作两面，此处重写为清晰的「检索 → 生成 → 校验」三段。

### 9.1 三段 + 关卡边界

```
员工提问(staff_language)
        │
   ┌────▼─────────────────────────────────────────┐
   │ ① 检索段（调 LLM 之前，纯检索，不调 LLM）        │
   │   跨语种标准化：staff_language → kb_base_language│
   │   向量检索：pgvector，强制 tenant filter         │
   │   阈值判断：top1 相似度 ≥ θ_retr ?               │
   │     否 → 兜底"暂无明确说明"（不进 LLM）           │
   └────┬─────────────────────────────────────────┘
        │ 命中，拿到 top-k 片段
   ┌────▼─────────────────────────────────────────┐
   │ ② 生成段（唯一一次 LLM 调用）                    │
   │   System Prompt 强约束：                        │
   │     · 只能基于【下方检索片段】作答                │
   │     · 不得编造、不得补常识、不得推理资料外内容     │
   │     · 涉及赔偿/承诺等敏感项 → 拒答               │
   │     · 引用必须从提供的片段 ID 中【选择】，不得自造  │
   │   输出：答案 + 所选片段 ID（source_refs）          │
   └────┬─────────────────────────────────────────┘
        │
   ┌────▼─────────────────────────────────────────┐
   │ ③ 校验段（非 LLM：规则 + 向量相似度）            │
   │     · 引用的片段 ID 必须 ∈ 本次命中 top-k 集合     │
   │     · 答案与所选片段相似度 ≥ θ_check             │
   │   通过 → TTS(staff_language) 播给员工            │
   │   拦截 → 兜底话术                                │
   └──────────────────────────────────────────────┘
```

### 9.2 关键点

1. **范围判断 = 检索阈值**，不是单独一次 LLM 分类。检索不到直接兜底，**省掉一次 LLM 调用的时延和成本**。
2. **整个流程只有一次 LLM 调用**（②）；③ 校验段用**非 LLM 手段**（片段 ID 白名单 + 向量相似度），避免二次 LLM 调用的时延与成本。
3. **source_refs 做强约束**：引用**强制选自命中的 top-k 片段**（不允许 LLM 自造引用），从机制上堵死编造。

### 9.3 source_refs 强约束（已定 A，修正绕过）

- ② 生成段：LLM 的引用**只能从本次命中的 top-k 片段 ID 中选择**，不允许自由生成引用（堵死「编造不存在段落」）。
- ③ 校验段（非 LLM）：校验「引用片段 ID ∈ 命中集合」+「答案与所选片段向量相似度 ≥ θ_check」，任一不满足即拦截兜底。
- 后台记录 `source_refs`（命中文档、版本、段落），用于追溯；设备端不强制显示。
- 需注意 Prompt 与 θ_check 校准，避免误拦截。

### 9.4 LLM 选型（已定 A）

- **Azure OpenAI（GPT 系列）**：日本东 region，与 Azure Speech 同云、数据驻留一致；结构化输出 / function calling 成熟。
- LLM 同样纳入 `provider-abstraction`，1.0 落地 Azure OpenAI 适配器，未来可扩展（不写死单供应商）。

### 9.5 连续问答语义（SW-SKB-003）

- **1.0「连续」= 状态连续**：进入问答状态后可持续多次提问，每轮回答结束保持可提问，直至员工退出或超时；**每轮独立检索回答，不携带多轮上下文**。
- 多轮上下文（追问指代，如「那宠物呢？」）作为 2.0 增强，1.0 不承诺。
- 明确此语义，避免验收时误解「连续」为「多轮上下文」。

---

## 10. 错误码与降级表

### 10.1 铁律：错误码只管设备端行为，云端降级是另一条线

- **错误码（云→端下行）**：告诉设备「现在该怎么表现」——重说、重试、兜底、静默。设备只认这个。
- **云端降级（云端内部）**：供应商失败→切备选、超时→重试。**设备看不见、也不用管**。

设备不该知道「Google 挂了还是 Azure 挂了」，只需知道「这次没结果，请重说」。

### 10.2 错误码表（大类粗粒度，已定 A）

| 错误码 | 场景 | 设备端行为 | 来源 |
|---|---|---|---|
| `E_AUTH` | 鉴权失败 | 提示 + 重新鉴权 | 网关 |
| `E_NET` | 网络断开/超时 | 重连；提示音；**本地打断仍可用** | 网关 |
| `E_LID_LOW` | LID 低置信且非白名单 | 播「请再说一遍」；**不外放译文** | 薄层 |
| `E_PROVIDER` | 供应商全挂（已降级仍失败） | 播「服务暂不可用，稍后再试」 | 薄层 |
| `E_ASR_FAIL` | ASR 失败 | 提示重试 / 云端切备选供应商 | 薄层 |
| `E_QA_NO_MATCH` | 知识库无匹配 | 播「暂无明确说明」兜底 | skb |
| `E_QA_OUT_OF_SCOPE` | 范围外问题 | 播兜底话术（可配置） | skb |
| `E_CONFLICT` | 输入来源/语言冲突、重叠讲话 | 提示「请依次说话/重新说」；不播放 | 薄层 |
| `E_SAME_LANG` | 双方同语言、游客说 staff_language | 不翻译 / 直通 / 提示重说 | 薄层 |
| `E_SESSION` | 会话被云端强制终止（强制下线/解绑/吊销） | 结束当前会话，回待机 | 网关 |

### 10.3 降级语义

1. **`E_LID_LOW` 是翻译链路最核心的降级**：低置信 → 不外放高风险译文 → 提示重说 / 白名单直通（仅 LOCKED 状态）（0804 §0.3「不确定不猜测」的直接落点）。
2. **`E_PROVIDER` 是「最后兜底」**（已定 A）：云端先做「供应商内重试 + 跨供应商切换」，**所有供应商都失败才下发**。设备只看到最终结果，不参与降级决策。
3. **`E_QA_NO_MATCH` ≠ 错误**：是「知识库没有明确答案」的正常业务结果，不是异常。设备播「暂无明确说明」，不记 error 日志（记未命中日志，供内容补充）。
4. **`E_NET` 下打断不失效**：网络断时端侧 TTS 打断（§6）照常工作——这是断网场景唯一还活着的交互，错误码绝不卡死它。
5. **断线重连语义**（与「会话级短连」一致）：网络断线 = 会话结束、`visitor_language` 清空；重连后进入 UNKNOWN，设备自动恢复不需重启。此语义须写入接口规范，避免 ODM 与云端对「重连后语种记忆」各实现一套。

---

## 11. 配置下发与后台 H5

### 11.1 配置通道（已定 A：复用 WSS + pull/push 混合）

设备是**会话级短连**、平时睡眠不在线（0804 §7.2「不要求长期常连接」），故 MQTT 长连「随时推」的优势用不上——设备睡眠时 MQTT 也断，唤醒时才需要配置。不引入 MQTT broker，复用 WSS 接入层：

```
后台改配置 ──► 存库 ──► ┌─ 设备在线(会话中)：WSS config_update 推送
                        └─ 设备唤醒/重连：主动 pull 最新配置（版本号比对，只拉变更）
```

少一个 broker = 少一个故障点 + 少一套运维。

### 11.2 配置分类

| 类型 | 例子 | 生效时机 |
|---|---|---|
| 静态配置 | lang_pairs / LID 阈值 / 白名单 / 音量 / endpoint | 设备唤醒 pull 时生效 |
| 会话动态 | 兜底话术更新 / 强制扬声器 | 会话中 `config_update` 推，低频 |

### 11.3 后台 H5 领域（admin 模块）

基于 0804 §11 + 已定模块，admin 领域（不展开字段细节）：

```
admin 领域：
  设备域    设备绑定/分组/在线状态/模式/语言/版本/网络
  语言策略域  LID阈值/软锁定参数/白名单/品牌词表/数字过滤
  知识域     skb 资料导入/分类/版本/审核/发布（vkb 预留）
  权限域     RBAC（platform/customer/store 三级）
  日志域     语言/网络/耳机/路由/候选/问答/延迟/错误/成本
```

### 11.4 staff_language 生命周期（SW-TR-002）

- 设置入口：开机引导 / 设备菜单 / H5 / 后台（选择「我的语言」）。
- 多人共用：每班次开始确认当前员工语言，或后台临时下发；重启后按配置保留。
- 下发通道：作为静态配置，设备唤醒 pull 时生效（§11.2）。
- 关系：staff_language 独立于 visitor_language（后者会话级、会清空；前者配置级、持久保留）。

### 11.5 OTA 与设备身份（P0 最小设计）

- 设备 UUID：设备首次激活时由云端签发（device_id），设备保存「甲方签发的设备身份 + 短期凭证」。
- 凭证生命周期：token 签发、过期、刷新、吊销（真实上线需短期凭证轮换；demo 可用静态 token，接口钩子预留）。
- OTA：ESP32-P4 主固件 / ESP32-C5 无线固件 / IA8201 参数三类版本管理 + 远程升级；升级依赖、顺序、完整性校验、失败回滚、断电恢复由 ODM 定义，云端提供版本下发通道与兼容矩阵校验。
- 断线重连：设备断网后自动重连（不需重启），语义见 §10.3 第 5 条。

---

## 附录 A：demo scope 决策表（1.0）

| 能力 | demo 范围 | 说明 |
|---|---|---|
| 翻译主链路（双向 + AUTO/固定语言对 + LID 软锁定） | ✅ 做 | 核心演示价值 |
| TTS 打断（端侧本地 ≤100ms） | ✅ 做 | 连续对话体验 |
| 员工知识库 skb（受控 RAG） | ✅ 做 | 差异化价值 |
| 游客知识库 vkb | ❌ 后置 | 已批准范围裁剪（见 §0 偏离记录） |
| 耳机辅助（非对称路由） | ❌ 后置 | demo 默认扬声器外放；字段预留，逻辑后置 |
| 原始音频旁路回传 | ❌ 默认关 | 仅脱敏文本；合规审批后开 |
| 私有化部署 | ❌ 后置 | 架构留 provider-abstraction + 部署开关钩子 |

## 附录 B：技术风险与依赖（前置验证项）

1. **LID 置信度可获取性**（P0 前置）：Azure/Google 的 LID 是否输出 per-utterance 语言置信度、覆盖候选语种，须选型实测；否则软锁定退化。
2. **时延达标**：Google 三步串行（TTS 首帧须等 MT 完整文本）是瓶颈，需实测；体感延迟含 ~1.2s VAD 静音，验收口径须声明。
3. **供应商限流/配额**：Google/Azure QPS/并发/字符配额，需配额预留 + 限流退避 + 告警。
4. **pgvector HNSW + tenant filter 召回**：小规模无碍；资料量上升后 pre-filter 召回不足，预留「每 tenant 独立索引」方案。
5. **供应商超时/重试预算**：单供应商超时（建议 1-1.5s 快速失败）+ 整链路总超时预算，避免降级路径时延雪崩。
6. **背压/流控/幂等**：TTS 下行流控、音频上行积压丢弃、断线重连幂等（session_id+sequence 去重）、连接/缓冲上限、会话 TTL 清理、监控告警——writing-plans 阶段细化。

## 附录 C：后置到 writing-plans 阶段的内容

- 供应商适配器流式传输细节（分片大小、鉴权、重试）。
- 背压/流控、幂等、连接管理、监控告警的具体实现。
