# PostgreSQL 16 + pgvector Docker 部署指南

用于 AI 工牌后端：主数据库 + 员工知识库 RAG 向量检索。

## 1. 镜像选择

使用官方 pgvector 预编译镜像，已包含 PostgreSQL 16 与 `pgvector` 扩展：

- 镜像：`pgvector/pgvector:pg16`
- 镜像仓库：https://hub.docker.com/r/pgvector/pgvector

## 2. 快速启动（docker run）

```bash
docker run -d \
  --name aicard-postgres \
  -e POSTGRES_DB=aicard \
  -e POSTGRES_USER=aicard \
  -e POSTGRES_PASSWORD=aicard \
  -p 5432:5432 \
  -v aicard-pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

## 3. 推荐方式（docker-compose.yml）

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: aicard-postgres
    environment:
      POSTGRES_DB: aicard
      POSTGRES_USER: aicard
      POSTGRES_PASSWORD: aicard
    ports:
      - "5432:5432"
    volumes:
      - aicard-pgdata:/var/lib/postgresql/data
    restart: unless-stopped

volumes:
  aicard-pgdata:
```

启动：

```bash
docker-compose up -d
```

## 4. 启用 pgvector 扩展

进入容器并连接数据库：

```bash
docker exec -it aicard-postgres psql -U aicard -d aicard
```

执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

退出：

```sql
\q
```

## 5. 验证

```bash
docker exec -it aicard-postgres psql -U aicard -d aicard -c "SELECT * FROM pg_extension WHERE extname = 'vector';"
```

看到 `vector` 行即表示扩展已启用。

## 6. 清理与重建

```bash
docker stop aicard-postgres
docker rm aicard-postgres
# 如需清空数据：
docker volume rm aicard-pgdata
```

## 7. AI 工牌后端连接配置

`application.yml` 示例：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aicard
    username: aicard
    password: aicard
  jpa:
    hibernate:
      ddl-auto: validate
```

生产环境请替换强密码，并将 `localhost` 改为实际数据库地址。
