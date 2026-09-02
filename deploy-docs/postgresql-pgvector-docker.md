# PostgreSQL 16 + pgvector Docker 部署指南

用于 AI 工牌后端：主数据库 + 员工知识库 RAG 向量检索。

> 部署方式：先以 `root` 超级用户启动容器，启动后再用客户端手动创建业务数据库 `aicard`、业务用户及表结构。

## 1. 环境说明

- 镜像：`pgvector/pgvector:pg16`
- 容器名：`aicard-postgres`
- 超级用户：`root`（实际密码见 `docker-compose.yml`）
- 默认库：`root`
- 业务库：`aicard`（启动后手动创建）
- 业务用户：`aicard`（启动后手动创建）
- 数据目录（宿主机）：`/datasdb1/docker-env/postgresql16-vector-env/data`
- 暴露端口：`10.0.189.32:5432`

## 2. 数据目录准备

创建宿主机数据目录并设置 PostgreSQL 用户权限（容器内 `postgres` uid 为 `999`）：

```bash
sudo mkdir -p /datasdb1/docker-env/postgresql16-vector-env/data
sudo chown -R 999:999 /datasdb1/docker-env/postgresql16-vector-env/data
sudo chmod -R 700 /datasdb1/docker-env/postgresql16-vector-env/data
```

> 如果在 macOS Docker Desktop 上测试，可直接跳过 `chown 999:999`，Docker Desktop 会自动处理权限映射。

## 3. docker-compose 方式启动

`docker-compose.yml` 已位于 `deploy-docs` 目录，当前配置如下：

```yaml
version: "3.8"

services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: aicard-postgres
    environment:
      TZ: Asia/Tokyo
      POSTGRES_USER: root
      POSTGRES_PASSWORD: <your-password>
      POSTGRES_DB: root
    ports:
      - "10.0.189.32:5432:5432"
    volumes:
      - /datasdb1/docker-env/postgresql16-vector-env/data:/var/lib/postgresql/data
    shm_size: 256m
    ulimits:
      nofile:
        soft: 65536
        hard: 65536
    mem_limit: 2g
    cpus: "2.0"
    restart: always
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U root -d root"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
```

> 请将 `<your-password>` 替换为 `docker-compose.yml` 中实际配置的强密码。

启动服务：

```bash
cd /Users/wayne23/Documents/ai-workspace/ai-card-space/AiCard/deploy-docs
docker-compose up -d
```

查看日志：

```bash
docker-compose logs -f postgres
```

## 4. 快速启动（docker run）

如果不使用 docker-compose：

```bash
docker run -d \
  --name aicard-postgres \
  -e TZ=Asia/Tokyo \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD=<your-password> \
  -e POSTGRES_DB=root \
  -p 10.0.189.32:5432:5432 \
  -v /datasdb1/docker-env/postgresql16-vector-env/data:/var/lib/postgresql/data \
  --restart always \
  pgvector/pgvector:pg16
```

## 5. 手动创建业务数据库与用户

容器启动后，以 `root` 身份登录，创建 `aicard` 数据库和业务用户：

```bash
docker exec -it aicard-postgres psql -U root -d root
```

```sql
-- 创建业务数据库（UTF8 + C.UTF-8 locale，兼容多语种）
CREATE DATABASE aicard
    WITH
    ENCODING = 'UTF8'
    LC_COLLATE = 'C.UTF-8'
    LC_CTYPE = 'C.UTF-8'
    TEMPLATE = template0;

-- 创建业务用户
CREATE USER aicard WITH PASSWORD '<your-password>';

-- 授予业务用户对 aicard 数据库的连接权限
GRANT ALL PRIVILEGES ON DATABASE aicard TO aicard;

\c aicard

-- 授权业务用户在 public schema 下创建表
GRANT CREATE, USAGE ON SCHEMA public TO aicard;

-- 创建 pgvector 扩展（需要超级用户或数据库 owner 权限）
CREATE EXTENSION IF NOT EXISTS vector;

\q
```

> 业务用户 `aicard` 的密码建议与 `docker-compose.yml` 中的 `root` 密码不同，并在 `application.yml` 中对应填写。

## 6. 验证

查看 `vector` 扩展是否启用：

```bash
docker exec -it aicard-postgres psql -U aicard -d aicard -c "SELECT * FROM pg_extension WHERE extname = 'vector';"
```

看到 `vector` 行即表示扩展已启用。

## 7. 后端连接配置

`application.yml` 示例（使用业务用户）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://10.0.189.32:5432/aicard
    username: aicard
    password: <your-password>
  jpa:
    hibernate:
      ddl-auto: validate
```

## 8. 清理与重建

停止并删除容器：

```bash
docker-compose down
# 或
docker stop aicard-postgres && docker rm aicard-postgres
```

如需彻底清空数据，删除宿主机目录内容：

```bash
sudo rm -rf /datasdb1/docker-env/postgresql16-vector-env/data
```
