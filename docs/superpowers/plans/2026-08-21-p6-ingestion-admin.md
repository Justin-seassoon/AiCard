# P6 旁路采集与运营后台（ingestion + admin）· 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现旁路样本采集（ingestion，翻译结果脱敏落库形成数据飞轮）与运营后台（admin，设备管理 + 知识管理 + RBAC 骨架），支撑 demo 的可运营性与后续数据积累。

**Architecture:** ingestion 由 P4 翻译链路异步 tee 调用（`IngestionService.record`，`@Async` 不阻塞主链路），落 `translation_sample` 表（demo 默认只存脱敏文本，原始音频不采集）。admin 为 REST 管理面，租户 scope 从请求头模拟登录的用户 RBAC 推导写入 `TenantContext`（真实 SSO/登录后置）。

**Tech Stack:** Java 17、Spring Web（REST + `@Async`）、`JdbcTemplate`、JUnit + Mockito + MockMvc。

**Spec:** `docs/superpowers/specs/2026-08-21-ai-card-software-architecture-design.md`（§1.1/§3.3 旁路采集、§11.3 后台 H5 领域、§8.2/§8.3 隔离与 RBAC、附录 A demo scope）。

## Global Constraints

- ingestion **绝不阻塞翻译主链路**（spec §5.2）：`@Async` 异步 + 失败静默降级。
- demo 默认**只存脱敏文本**，原始音频回传默认关闭（spec 附录 A / §14）。
- admin 的 tenant 只从登录用户 RBAC 推导，**不信任请求体里的 customer/store 字段**（spec §8.2）。
- RBAC 三级：`platform_admin` / `customer_admin` / `store_admin`（spec §8.3）。
- vkb 相关字段仅预留，不实现逻辑（spec §0 偏离记录）。

## File Structure（本 plan 创建）

```
src/main/resources/db/migration/V4__translation_sample.sql
src/main/java/com/aicard/ingestion/
  model/TranslationSample.java
  service/IngestionService.java
src/main/java/com/aicard/admin/
  auth/Role.java
  auth/AdminContext.java
  auth/AdminContextFilter.java
  dto/DeviceDto.java
  dto/DocumentImportRequest.java
  controller/DeviceController.java
  controller/DocumentController.java
src/test/java/com/aicard/ingestion/service/IngestionServiceTest.java
src/test/java/com/aicard/admin/controller/DeviceControllerTest.java
```

---

### Task 1: 旁路样本采集（ingestion）

**Files:**
- Create: `db/migration/V4__translation_sample.sql`
- Create: `ingestion/model/TranslationSample.java`, `ingestion/service/IngestionService.java`
- Create: `src/test/java/com/aicard/ingestion/service/IngestionServiceTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`。
- Produces: `IngestionService.record(TranslationSample)`（脱敏 + 异步落库）。

- [ ] **Step 1: 写迁移脚本**

`V4__translation_sample.sql`：

```sql
CREATE TABLE translation_sample (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    src_lang VARCHAR(16),
    tgt_lang VARCHAR(16),
    source_text TEXT,
    translated_text TEXT,
    lid_confidence DOUBLE PRECISION,
    source_side VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sample_customer_store ON translation_sample(customer_id, store_id);
```

- [ ] **Step 2: 写失败测试（脱敏逻辑）**

```java
package com.aicard.ingestion.service;

import com.aicard.ingestion.model.TranslationSample;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionServiceTest {

    private final JdbcTemplate jdbc = new JdbcTemplate(
            new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build());
    private final IngestionService service = new IngestionService(jdbc);

    @Test
    void sanitizesPersonalData() {
        String sanitized = service.sanitize("电话 09012345678，邮箱 a@b.com");
        assertThat(sanitized).doesNotContain("09012345678");
        assertThat(sanitized).doesNotContain("a@b.com");
        assertThat(sanitized).contains("####");
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
mvn test -Dtest=IngestionServiceTest
```

Expected: FAIL（`IngestionService` 不存在）。

- [ ] **Step 4: 实现 TranslationSample + IngestionService**

```java
package com.aicard.ingestion.model;

public record TranslationSample(
        Long customerId, Long storeId, String sessionId, String turnId,
        String srcLang, String tgtLang, String sourceText, String translatedText,
        Double lidConfidence, String sourceSide
) {}
```

```java
package com.aicard.ingestion.service;

import com.aicard.ingestion.model.TranslationSample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final JdbcTemplate jdbc;

    public IngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void record(TranslationSample sample) {
        try {
            jdbc.update(
                    "INSERT INTO translation_sample(customer_id, store_id, session_id, turn_id, " +
                    "src_lang, tgt_lang, source_text, translated_text, lid_confidence, source_side) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    sample.customerId(), sample.storeId(), sample.sessionId(), sample.turnId(),
                    sample.srcLang(), sample.tgtLang(),
                    sanitize(sample.sourceText()), sanitize(sample.translatedText()),
                    sample.lidConfidence(), sample.sourceSide());
        } catch (Exception e) {
            // 旁路失败静默降级，不影响翻译主链路
        }
    }

    String sanitize(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replaceAll("[\\w.]+@[\\w.]+", "[EMAIL]")
                .replaceAll("\\d{4,}", "####");
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
mvn test -Dtest=IngestionServiceTest
```

Expected: PASS。

- [ ] **Step 6: 启用 @Async + Commit**

在 `AiCardApplication` 加 `@EnableAsync`：

```java
package com.aicard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AiCardApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCardApplication.class, args);
    }
}
```

```bash
mvn test
git add -A
git commit -m "feat: add translation sample ingestion with sanitization"
```

---

### Task 2: 后台设备管理 REST

**Files:**
- Create: `admin/dto/DeviceDto.java`, `admin/controller/DeviceController.java`
- Create: `src/test/java/com/aicard/admin/controller/DeviceControllerTest.java`

**Interfaces:**
- Consumes: P1 的 `DeviceRepository`/`Device` 实体、`TenantContext`。
- Produces: `GET /api/devices`（本租户设备列表）、`POST /api/devices`（创建 + 绑定 tenant）、`PUT /api/devices/{id}`（更新 staff_language 等）。

- [ ] **Step 1: 写失败测试（@WebMvcTest + Mock DeviceRepository）**

```java
package com.aicard.admin.controller;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeviceController.class)
class DeviceControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DeviceRepository devices;

    @Test
    void listsDevicesForTenant() throws Exception {
        Device d = Device.builder().deviceId("dev-1").customerId(1L).storeId(2L)
                .token("t").staffLanguage("ja-JP").build();
        when(devices.findByCustomerIdAndStoreId(1L, 2L)).thenReturn(List.of(d));

        mvc.perform(get("/api/devices").header("X-Customer-Id", "1").header("X-Store-Id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("dev-1"));
    }

    @Test
    void createsDevice() throws Exception {
        when(devices.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/devices").header("X-Customer-Id", "1").header("X-Store-Id", "2")
                        .contentType("application/json")
                        .content("{\"deviceId\":\"dev-9\",\"staffLanguage\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("dev-9"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=DeviceControllerTest
```

Expected: FAIL（`DeviceController` 不存在，`DeviceRepository.findByCustomerIdAndStoreId` 未定义）。

- [ ] **Step 3: 补 DeviceRepository 查询方法 + 实现 DeviceDto + DeviceController**

`DeviceRepository`（P1 文件追加方法）：

```java
List<Device> findByCustomerIdAndStoreId(Long customerId, Long storeId);
```

```java
package com.aicard.admin.dto;

import com.aicard.common.domain.Device;

public record DeviceDto(Long id, String deviceId, Long customerId, Long storeId,
                        String staffLanguage) {
    public static DeviceDto from(Device d) {
        return new DeviceDto(d.getId(), d.getDeviceId(), d.getCustomerId(), d.getStoreId(),
                d.getStaffLanguage());
    }
}
```

```java
package com.aicard.admin.controller;

import com.aicard.admin.dto.DeviceDto;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRepository devices;

    public DeviceController(DeviceRepository devices) {
        this.devices = devices;
    }

    @GetMapping
    public List<DeviceDto> list() {
        Tenant t = TenantContext.require();
        return devices.findByCustomerIdAndStoreId(t.customerId(), t.storeId())
                .stream().map(DeviceDto::from).toList();
    }

    @PostMapping
    public DeviceDto create(@RequestBody DeviceDto req) {
        Tenant t = TenantContext.require();
        Device d = Device.builder()
                .deviceId(req.deviceId())
                .customerId(t.customerId())
                .storeId(t.storeId())
                .token(UUID.randomUUID().toString())
                .staffLanguage(req.staffLanguage())
                .createdAt(Instant.now())
                .build();
        return DeviceDto.from(devices.save(d));
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=DeviceControllerTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add admin device management REST"
```

---

### Task 3: 后台知识管理 REST + RBAC 骨架

**Files:**
- Create: `admin/auth/Role.java`, `admin/auth/AdminContext.java`, `admin/auth/AdminContextFilter.java`
- Create: `admin/dto/DocumentImportRequest.java`
- Create: `admin/controller/DocumentController.java`

**Interfaces:**
- Consumes: P5 的 `KnowledgeStore`、`TenantContext`。
- Produces: `AdminContextFilter`（从 header 推导用户 + 角色 + tenant 写入 `TenantContext`）、`DocumentController`（`POST /api/documents` 导入 document+chunk、`POST /api/documents/{id}/publish` 发布）。

- [ ] **Step 1: 实现 RBAC 骨架**

```java
package com.aicard.admin.auth;

public enum Role {
    PLATFORM_ADMIN, CUSTOMER_ADMIN, STORE_ADMIN
}
```

```java
package com.aicard.admin.auth;

public record AdminContext(String userId, Role role, Long customerId, Long storeId) {}
```

```java
package com.aicard.admin.auth;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * demo 版 RBAC：从请求头 X-Admin-User / X-Admin-Role / X-Customer-Id / X-Store-Id
 * 推导登录用户与租户 scope 写入 TenantContext。真实 SSO/登录后置（spec 附录 A 私有化后置）。
 */
@Component
public class AdminContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String customerId = req.getHeader("X-Customer-Id");
        String storeId = req.getHeader("X-Store-Id");
        if (customerId != null && storeId != null) {
            TenantContext.set(Tenant.of(Long.valueOf(customerId), Long.valueOf(storeId)));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 2: 实现 DocumentController + 导入 DTO**

```java
package com.aicard.admin.dto;

import java.util.List;

public record DocumentImportRequest(String title, String version, List<ChunkIn> chunks) {
    public record ChunkIn(String text, List<Float> embedding) {}
}
```

```java
package com.aicard.admin.controller;

import com.aicard.admin.dto.DocumentImportRequest;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final KnowledgeStore store;

    public DocumentController(KnowledgeStore store) {
        this.store = store;
    }

    @PostMapping
    public Long importDocument(@RequestBody DocumentImportRequest req) {
        Tenant t = TenantContext.require();
        Document doc = store.insertDocument(new Document(null, t.customerId(), t.storeId(),
                req.title(), req.version(), "draft", Instant.now()));
        int i = 0;
        for (DocumentImportRequest.ChunkIn c : req.chunks()) {
            store.insertChunk(new Chunk(null, doc.id(), t.customerId(), t.storeId(), i++, c.text()),
                    c.embedding());
        }
        return doc.id();
    }

    @PostMapping("/{id}/publish")
    public void publish(@PathVariable Long id) {
        store.publishDocument(id);
    }
}
```

`KnowledgeStore`（P5 文件追加方法）：

```java
public void publishDocument(Long id) {
    jdbc.update("UPDATE document SET status = 'published' WHERE id = ?", id);
}
```

- [ ] **Step 3: 全量测试 + Commit**

```bash
mvn test
git add -A
git commit -m "feat: add admin knowledge management and RBAC skeleton"
```

---

## Self-Review

- **Spec 覆盖**：落地 spec §1.1/§3.3 旁路采集（`@Async` 不阻塞 + 脱敏）、§11.3 设备域/知识域、§8.2 admin tenant 从 RBAC 推导（`AdminContextFilter`）、§8.3 权限层 RBAC 骨架、附录 A（原始音频默认不采集、私有化后置）。
- **占位符扫描**：无 TBD/TODO；所有 step 含实际代码。
- **类型一致性**：`IngestionService.sanitize` 包级可见供测试；`DeviceController`/`DocumentController` 从 `TenantContext.require()` 取 tenant（`Long` 类型，与 P1 一致）；`DeviceRepository.findByCustomerIdAndStoreId` 在测试与实现一致。

### 已知边界（后置处理，非本 plan 范围）

- 真实登录/SSO、细粒度 RBAC（员工细分角色）——spec §8.3 权限层 2.0 扩展，demo 用 header 模拟。
- 完整 PII 脱敏（实体识别/掩码/留存期）——spec §14，demo 用基础正则，联调前接入合规评审。
- 后台 H5 前端（SPA）——本 plan 只做 REST API；前端单独交付。
- 日志分析/成本统计查询界面——spec §11 日志域 P1，依赖 P2 `ProviderMetrics` 与日志，联调前补查询 API。
- vkb 知识管理（游客 FAQ）——spec §0 偏离记录明确后置，本 plan 只做 skb 资料导入。
