# P1 项目骨架与四层隔离基础设施 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 AI 工牌云端后端的模块化单体骨架，建立 PostgreSQL+pgvector 数据库 schema，实现四层隔离模型的数据层基础设施（可信租户上下文 + 仓储层强制 filter）。

**Architecture:** 单 Maven 模块 + 包内按子系统划分（`gateway`/`translation`/`skb`/`ingestion`/`admin`/`provider`/`common`）。本 plan 只落地 `common`（租户、领域、配置）与数据库 schema；其它包本 plan 仅建空目录占位，由后续 plan 填充。四层隔离的数据层：所有业务表带 `customer_id`/`store_id` 列，仓储查询强制从 `TenantContext` 取租户过滤。

**Tech Stack:** Java 17、Spring Boot 3.3.x、Maven、Spring Data JPA、PostgreSQL 16 + pgvector、Flyway、Testcontainers、Lombok。

**Spec:** `docs/superpowers/specs/2026-08-21-ai-card-software-architecture-design.md`（§8 四层隔离模型、§2 模块边界、附录 A demo scope）。

## Global Constraints

- Java 17；Spring Boot 3.3.x；Maven 单模块；包根 `com.aicard`。
- 数据库：PostgreSQL 16 + pgvector 扩展（测试用 Testcontainers，镜像 `pgvector/pgvector:pg16`）。
- 迁移：Flyway，脚本在 `src/main/resources/db/migration/`。
- 租户隔离铁律（spec §8.2）：`customer_id`/`store_id` 一律来自 `TenantContext`（可信上下文），**绝不信任请求参数**。
- 本项目尚未 git init：Task 1 第一步执行 `git init` 与 `.gitignore`。
- 命名：包名小写；实体/仓储用领域名；表名单数。
- Lombok 用于 `@Getter/@Setter/@Builder` 等样板。

## File Structure（本 plan 创建）

```
pom.xml
.gitignore
src/main/java/com/aicard/
  AiCardApplication.java
  common/tenant/Tenant.java
  common/tenant/TenantContext.java
  common/tenant/TenantContextFilter.java
  common/domain/Customer.java
  common/domain/Store.java
  common/domain/Device.java
  common/repository/CustomerRepository.java
  common/repository/StoreRepository.java
  common/repository/DeviceRepository.java
  gateway/            (空目录，后续 P3)
  translation/        (空目录，后续 P4)
  skb/                (空目录，后续 P5)
  ingestion/          (空目录，后续 P6)
  admin/              (空目录，后续 P6)
  provider/           (空目录，后续 P2)
src/main/resources/
  application.yml
  db/migration/V1__init.sql
  db/migration/V2__pgvector.sql
src/test/java/com/aicard/
  AiCardApplicationTest.java
  common/tenant/TenantIsolationTest.java
  common/tenant/TenantContextTest.java
src/test/resources/application-test.yml
```

---

### Task 1: 项目骨架与依赖

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/main/java/com/aicard/AiCardApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`
- Create: `src/test/java/com/aicard/AiCardApplicationTest.java`
- Create: 空目录 `gateway/`, `translation/`, `skb/`, `ingestion/`, `admin/`, `provider/`（各放一个 `.gitkeep`）

**Interfaces:**
- 产出：可 `mvn test` 的可编译 Maven 工程；Spring 上下文能借助 Testcontainers 启动。

- [ ] **Step 1: git init + .gitignore**

```bash
cd /Users/jianghua/aiCard
git init
```

创建 `.gitignore`：

```gitignore
target/
*.class
.idea/
*.iml
.vscode/
.DS_Store
.env
*.log
```

- [ ] **Step 2: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>com.aicard</groupId>
  <artifactId>aicard</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>aicard</name>
  <description>AI 工牌 1.0 云端后端</description>

  <properties>
    <java.version>17</java.version>
    <testcontainers.version>1.20.1</testcontainers.version>
    <pgvector.version>0.1.6</pgvector.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.pgvector</groupId>
      <artifactId>pgvector</artifactId>
      <version>${pgvector.version}</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 写主类与配置**

`AiCardApplication.java`：

```java
package com.aicard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiCardApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCardApplication.class, args);
    }
}
```

`application.yml`：

```yaml
spring:
  application:
    name: aicard
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/aicard}
    username: ${DB_USER:aicard}
    password: ${DB_PASSWORD:aicard}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

`application-test.yml`：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

- [ ] **Step 4: 写 context loads 测试**

`AiCardApplicationTest.java`：

```java
package com.aicard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AiCardApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard")
            .withUsername("aicard")
            .withPassword("aicard");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn test
```

Expected: BUILD SUCCESS（`contextLoads` 通过；注意首次运行 Flyway 因无迁移脚本而空跑，Task 2 补脚本后 `ddl-auto: validate` 才会真正校验）。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: scaffold Spring Boot modular monolith (Java 17, JPA, pgvector, flyway, testcontainers)"
```

---

### Task 2: 数据库 schema（Flyway 迁移）

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/resources/db/migration/V2__pgvector.sql`

**Interfaces:**
- 产出：customer / store / device 三表 + pgvector 扩展；`ddl-auto: validate` 通过。

- [ ] **Step 1: 写 V1 初始化脚本**

`V1__init.sql`：

```sql
CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_store_customer ON store(customer_id);

CREATE TABLE device (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL REFERENCES customer(id),
    store_id BIGINT NOT NULL REFERENCES store(id),
    token VARCHAR(255) NOT NULL,
    staff_language VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_customer_store ON device(customer_id, store_id);
```

- [ ] **Step 2: 写 V2 pgvector 扩展脚本**

`V2__pgvector.sql`：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 3: 运行测试确认迁移执行**

```bash
mvn test
```

Expected: BUILD SUCCESS（`contextLoads` 触发 Flyway 迁移，`ddl-auto: validate` 校验通过；若 `validate` 报实体/表不匹配，回 Task 2 修正表定义）。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add flyway migrations for customer/store/device and pgvector"
```

---

### Task 3: 领域实体（Customer / Store / Device）

**Files:**
- Create: `src/main/java/com/aicard/common/domain/Customer.java`
- Create: `src/main/java/com/aicard/common/domain/Store.java`
- Create: `src/main/java/com/aicard/common/domain/Device.java`
- Create: `src/main/java/com/aicard/common/repository/CustomerRepository.java`
- Create: `src/main/java/com/aicard/common/repository/StoreRepository.java`
- Create: `src/main/java/com/aicard/common/repository/DeviceRepository.java`
- Create: `src/test/java/com/aicard/common/domain/DomainMappingTest.java`

**Interfaces:**
- 产出：三个实体 + 三个 Spring Data 仓储；实体与 `V1__init.sql` 列映射一致。

- [ ] **Step 1: 写实体**

`Customer.java`：

```java
package com.aicard.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "customer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

`Store.java`：

```java
package com.aicard.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "store")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

`Device.java`：

```java
package com.aicard.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 64)
    private String deviceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "staff_language", length = 16)
    private String staffLanguage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

- [ ] **Step 2: 写仓储接口**

`CustomerRepository.java`：

```java
package com.aicard.common.repository;

import com.aicard.common.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
```

`StoreRepository.java`：

```java
package com.aicard.common.repository;

import com.aicard.common.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findByCustomerId(Long customerId);
}
```

`DeviceRepository.java`：

```java
package com.aicard.common.repository;

import com.aicard.common.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceId(String deviceId);
}
```

- [ ] **Step 3: 写映射测试（验证 save + find）**

`DomainMappingTest.java`：

```java
package com.aicard.common.domain;

import com.aicard.common.repository.CustomerRepository;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DomainMappingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CustomerRepository customers;
    @Autowired StoreRepository stores;
    @Autowired DeviceRepository devices;

    @Test
    void persistsAndReadsDevice() {
        Customer c = customers.save(Customer.builder().code("C1").name("酒店A").createdAt(Instant.now()).build());
        Store s = stores.save(Store.builder().code("S1").customerId(c.getId()).name("门店1").createdAt(Instant.now()).build());
        Device d = devices.save(Device.builder().deviceId("dev-001").customerId(c.getId()).storeId(s.getId())
                .token("t1").staffLanguage("ja-JP").createdAt(Instant.now()).build());

        assertThat(devices.findByDeviceId("dev-001")).isPresent()
                .get().extracting(Device::getStaffLanguage).isEqualTo("ja-JP");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add customer/store/device entities and repositories"
```

---

### Task 4: 四层隔离的数据层（TenantContext + 强制 filter）

**Files:**
- Create: `src/main/java/com/aicard/common/tenant/Tenant.java`
- Create: `src/main/java/com/aicard/common/tenant/TenantContext.java`
- Create: `src/main/java/com/aicard/common/tenant/TenantContextFilter.java`
- Create: `src/main/java/com/aicard/common/repository/DeviceScopedRepository.java`（强制 tenant filter 的查询接口）
- Create: `src/test/java/com/aicard/common/tenant/TenantContextTest.java`
- Create: `src/test/java/com/aicard/common/tenant/TenantIsolationTest.java`

**Interfaces:**
- Consumes: `Device` 实体（Task 3）。
- Produces:
  - `Tenant(String customerId, String storeId)` record；
  - `TenantContext.set/get/require/clear`（ThreadLocal 可信上下文）；
  - `TenantContextFilter`（Servlet Filter，从鉴权结果写入上下文——P3 前先用测试直设）；
  - `DeviceScopedRepository.findByDeviceIdScoped(String deviceId)`：查询**必须**带当前 TenantContext 的 `customer_id`+`store_id` 过滤。

- [ ] **Step 1: 写 Tenant + TenantContext（先写失败测试）**

`TenantContextTest.java`：

```java
package com.aicard.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void requireThrowsWhenNotSet() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setAndGetRoundTrip() {
        TenantContext.set(Tenant.of(1L, 2L));
        assertThat(TenantContext.require()).isEqualTo(Tenant.of(1L, 2L));
    }

    @Test
    void clearRemovesContext() {
        TenantContext.set(Tenant.of(1L, 2L));
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=TenantContextTest
```

Expected: FAIL（`Tenant`/`TenantContext` 不存在，编译失败）。

- [ ] **Step 3: 实现 Tenant + TenantContext**

`Tenant.java`：

```java
package com.aicard.common.tenant;

public record Tenant(Long customerId, Long storeId) {
    public static Tenant of(Long customerId, Long storeId) {
        return new Tenant(customerId, storeId);
    }
}
```

`TenantContext.java`：

```java
package com.aicard.common.tenant;

public final class TenantContext {
    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) { HOLDER.set(tenant); }
    public static Tenant get() { return HOLDER.get(); }
    public static Tenant require() {
        Tenant t = HOLDER.get();
        if (t == null) {
            throw new IllegalStateException("tenant context not set");
        }
        return t;
    }
    public static void clear() { HOLDER.remove(); }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=TenantContextTest
```

Expected: PASS。

- [ ] **Step 5: 写隔离仓储的失败测试**

`TenantIsolationTest.java`：

```java
package com.aicard.common.tenant;

import com.aicard.common.domain.Customer;
import com.aicard.common.domain.Device;
import com.aicard.common.domain.Store;
import com.aicard.common.repository.CustomerRepository;
import com.aicard.common.repository.DeviceScopedRepository;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.repository.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TenantIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CustomerRepository customers;
    @Autowired StoreRepository stores;
    @Autowired DeviceRepository devices;
    @Autowired DeviceScopedRepository scopedDevices;

    private Long tenantACustomerId;
    private Long tenantAStoreId;
    private Long tenantBCustomerId;
    private Long tenantBStoreId;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        devices.deleteAll();
        stores.deleteAll();
        customers.deleteAll();
    }

    private void seed() {
        Customer a = customers.save(Customer.builder().code("A").name("A").createdAt(Instant.now()).build());
        Customer b = customers.save(Customer.builder().code("B").name("B").createdAt(Instant.now()).build());
        Store sa = stores.save(Store.builder().code("SA").customerId(a.getId()).name("SA").createdAt(Instant.now()).build());
        Store sb = stores.save(Store.builder().code("SB").customerId(b.getId()).name("SB").createdAt(Instant.now()).build());
        tenantACustomerId = a.getId();
        tenantAStoreId = sa.getId();
        tenantBCustomerId = b.getId();
        tenantBStoreId = sb.getId();
        devices.save(Device.builder().deviceId("dev-A").customerId(a.getId()).storeId(sa.getId()).token("t").createdAt(Instant.now()).build());
        devices.save(Device.builder().deviceId("dev-B").customerId(b.getId()).storeId(sb.getId()).token("t").createdAt(Instant.now()).build());
    }

    @Test
    void scopedQueryReturnsOwnTenantDevice() {
        seed();
        TenantContext.set(Tenant.of(tenantACustomerId, tenantAStoreId));
        Optional<Device> found = scopedDevices.findByDeviceIdScoped("dev-A");
        assertThat(found).isPresent();
    }

    @Test
    void scopedQueryBlocksOtherTenantDevice() {
        seed();
        TenantContext.set(Tenant.of(tenantACustomerId, tenantAStoreId));
        Optional<Device> found = scopedDevices.findByDeviceIdScoped("dev-B");
        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 6: 运行测试确认失败**

```bash
mvn test -Dtest=TenantIsolationTest
```

Expected: FAIL（`DeviceScopedRepository` 不存在）。

- [ ] **Step 7: 实现 DeviceScopedRepository**

`DeviceScopedRepository.java`：

```java
package com.aicard.common.repository;

import com.aicard.common.domain.Device;
import com.aicard.common.tenant.TenantContext;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

@Repository
public class DeviceScopedRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<Device> findByDeviceIdScoped(String deviceId) {
        var tenant = TenantContext.require();
        return em.createQuery(
                "SELECT d FROM Device d WHERE d.deviceId = :deviceId " +
                "AND d.customerId = :customerId AND d.storeId = :storeId", Device.class)
            .setParameter("deviceId", deviceId)
            .setParameter("customerId", tenant.customerId())
            .setParameter("storeId", tenant.storeId())
            .getResultStream()
            .findFirst();
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

```bash
mvn test -Dtest=TenantIsolationTest
```

Expected: PASS（scopedQueryReturnsOwnTenantDevice 通过、scopedQueryBlocksOtherTenantDevice 通过，验证跨租户查询被隔离）。

- [ ] **Step 9: 写 TenantContextFilter（预留鉴权写入口，P3 接入）**

`TenantContextFilter.java`：

```java
package com.aicard.common.tenant;

import jakarta.servlet.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 每请求清理租户上下文。真正的 tenant 写入由鉴权层（P3 gateway）在鉴权成功后
 * 调用 TenantContext.set(...) 完成；本 Filter 仅在请求结束时清理，防止线程池复用串租户。
 */
@Component
public class TenantContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 10: 全量测试 + Commit**

```bash
mvn test
git add -A
git commit -m "feat: add tenant context and scoped repository for four-layer isolation"
```

---

### Task 5: 健康检查验证

**Files:**
- 无新文件（Actuator 已在 Task 1 引入，`management.endpoints.web.exposure.include: health,info` 已配）。
- Create: `src/test/java/com/aicard/common/tenant/HealthCheckTest.java`

**Interfaces:**
- 产出：`GET /actuator/health` 返回 `{"status":"UP"}`，验证数据库连通。

- [ ] **Step 1: 写健康检查测试**

`HealthCheckTest.java`：

```java
package com.aicard.common.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HealthCheckTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

```bash
mvn test
```

Expected: BUILD SUCCESS（health 返回 UP，含数据库健康指示）。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: verify actuator health endpoint against postgres"
```

---

## Self-Review

- **Spec 覆盖**：本 plan 落地 spec §8.1（租户层级 customer/store/device）、§8.2（可信上下文，请求参数不做依据——`DeviceScopedRepository` 只从 `TenantContext` 取租户）、§8.3 数据层（强制 filter）。§8.4 双知识库隔离、§8.6 pgvector 的向量检索在 P5 落地，本 plan 仅启用 pgvector 扩展。接口层/权限层 filter 的鉴权写入由 P3/P6 接入（本 plan 用 `TenantContextFilter` 预留清理点）。
- **占位符扫描**：无 TBD/TODO；所有 step 含实际代码。
- **类型一致性**：`Tenant(Long customerId, Long storeId)` 与 `Device` 实体的 `Long customerId/storeId` 列类型一致；`TenantContextTest`/`TenantIsolationTest` 均传 `Long`，`DeviceScopedRepository` 直接绑定，无类型转换。
