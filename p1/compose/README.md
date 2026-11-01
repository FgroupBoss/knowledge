# P1 部署与试点运行

P1 只包含 portal（Spring Boot）与网关，**复用 P0 已运行的 mysql / redis / kafka / minio**。
应用拆成两个角色，同一镜像、同一数据库：

| 角色 | 容器 | 启用内容 | 端口 |
| --- | --- | --- | --- |
| api | `kb-p1-api` | HTTP、上传、问答、审计写、outbox 发布 | 127.0.0.1:18181 |
| worker | `kb-p1-worker` | Kafka 消费、真退避推进、租约心跳 | 无 |
| gateway | `kb-p1-gateway` | TLS 终止、SSE 直通、限速 | 127.0.0.1:8080/8443 |

角色由 `KB_ROLE` 决定；`IngestConsumer`、`RetryPump`、`LeaseHeartbeat` 只在 `worker` 下装配，
`api` 不会与 worker 抢同一分区。两个角色共用同一 consumer group，因此水平扩容 worker 即扩容消费能力。

网络沿用 P0 的两层结构：`p1-backend`（即 `kb-p0_p0-backend`，**internal**，容器无外网路由）承载全部
服务间流量；仅 api 与 gateway 额外挂 `p1-ingress`（即 `kb-p0_p0-ingress`）才能把端口发布到宿主机回环。
只挂 internal 网络的服务即使写了 `ports:` 也不会真正发布——这是本地验证时最容易踩的坑。

## 1. 准备数据库与账号（#6：DDL/DML 拆分）

口令**不写进本文档**：真实值只存在于 `p1/compose/secrets/`（已被 `.gitignore` 排除），
建号时从文件读入。用 root 口令连 `kb-p0-mysql` 执行：

```bash
cd p1/compose
docker exec -i kb-p0-mysql mysql -uroot -p"$(cat ../../p0/compose/secrets/mysql_root_password.txt)" <<SQL
CREATE DATABASE IF NOT EXISTS kb_p1
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 迁移账号：只在启动时被 Flyway 使用，拥有 DDL
CREATE USER IF NOT EXISTS 'kb_p1_ddl'@'%' IDENTIFIED BY '$(cat secrets/p1_db_ddl_password.txt)';
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
  ON kb_p1.* TO 'kb_p1_ddl'@'%';

-- 运行账号：只有 DML；应用启动时会 SHOW GRANTS 校验，越权直接拒绝启动
CREATE USER IF NOT EXISTS 'kb_p1_app'@'%' IDENTIFIED BY '$(cat secrets/p1_db_password.txt)';
GRANT SELECT, INSERT, UPDATE, DELETE ON kb_p1.* TO 'kb_p1_app'@'%';
FLUSH PRIVILEGES;
SQL
```

账号名与口令一律由 secrets 文件提供；改口令后需同步 `KB_DB_*` 与测试用的 `KB_TEST_*` 环境变量。

`kb_p1_app` 不会执行任何 DDL：Flyway 使用 `KB_DB_DDL_USER` / `KB_DB_DDL_PASSWORD_FILE`。
`KB_DB_ENFORCE_LEAST_PRIVILEGE=true` 时，运行账号一旦带有 CREATE/DROP/ALTER/GRANT 等权限，
`DatabasePrivilegeGuard` 会在 `ApplicationReadyEvent` 抛错，容器无法进入就绪态。

## 2. 准备 Kafka 主题

```bash
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.ingest.requested.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.ingest.retry.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.document.delete.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.ingest.dlq.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.audit.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.wiki.sync.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.wiki.retract.v1 --partitions 3 --replication-factor 1
docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic kb.wiki.dlq.v1 --partitions 1 --replication-factor 1
```

`kb.audit.v1` 必须存在：审计 outbox 发布器与业务写在同一数据库事务链路上，
主题缺失会让 outbox 行长期持有 `FOR UPDATE SKIP LOCKED`，进而拖慢在线审计写入。

`kb.wiki.*` 三个主题是 P2 Wiki 同步/撤稿/死信通道，**schema 已冻结、生产方要到 P2-W2 才接线**：
现在建题是为了让 W2 接线时不再撞出"配置里有主题、集群里没有主题"的孤儿主题故障
（该欠账登记在 `p0/decisions/P1-backlog.md`）。分区数以 `p0/scripts/03-topics.sh` 为准，
`kb.wiki.dlq.v1` 用单分区（死信量小，且重放取证按时间有序更省事）。
主题与载荷的契约事实源见 `p0/contracts/kafka/README.md` 与 `p0/contracts/kafka/wiki-*.schema.json`。

## 3. 准备 MinIO 桶

原始文件桶默认 `kb-p1-originals-private`，私有、不对外暴露：

```bash
docker exec kb-p0-minio mc alias set local http://127.0.0.1:9000 \
  "$(cat p0/compose/secrets/minio_access_key.txt)" \
  "$(cat p0/compose/secrets/minio_secret_key.txt)" 2>/dev/null || true
docker exec kb-p0-minio mc mb --ignore-existing local/kb-p1-originals-private
docker exec kb-p0-minio mc anonymous set none local/kb-p1-originals-private
```

## 4. 凭据文件

`p1/compose/secrets/` 必须存在以下文件（末尾换行会被 `strip()` 裁掉）：

| 文件 | 用途 |
| --- | --- |
| `p1_db_user.txt` | 运行账号，默认 `kb_p1_app` |
| `p1_db_password.txt` | 运行账号口令 |
| `p1_db_ddl_password.txt` | 迁移账号口令 |
| `oidc_client_secret.txt` | OIDC client secret，**部署前必须替换占位值** |
| `../../p0/compose/secrets/redis_password.txt` | 复用 P0 Redis |
| `../../p0/compose/secrets/minio_access_key.txt` 等 | 复用 P0 MinIO |

P0 secrets 位置可用 `KB_P0_SECRETS` 覆盖。文件带 CRLF 也能用：读取处统一 `strip()`，
避免 MinIO 签名多一个 `\r` 导致 SignatureDoesNotMatch。

## 5. TLS 证书

网关加载 `nginx/certs/tls.crt` 与 `tls.key`。证书不入库，试点机本地生成：

```bash
mkdir -p nginx/certs
# Git Bash on Windows 会把 /CN=... 当成路径重写，必须关掉 MSYS 路径转换
MSYS_NO_PATHCONV=1 openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout nginx/certs/tls.key -out nginx/certs/tls.crt \
  -subj "/CN=kb.example.internal" \
  -addext "subjectAltName=DNS:kb.example.internal,DNS:localhost,IP:127.0.0.1"
chmod 600 nginx/certs/tls.key
```

`kb_session` cookie 默认 `Secure`，所以必须经 8443 访问；8080 只做 308 跳转。

## 6. 启动

```bash
cd p1/compose
cp .env.example .env         # 修改 OIDC、secrets 路径
docker build -t kb-p1-portal:1.0.0 ../portal
docker compose --env-file .env up -d
docker compose ps
curl -k https://127.0.0.1:8443/healthz
```

本地无 IdP 时只能做启动冒烟：临时加 `KB_DEV_SUBJECT_ENABLED=true` 且 `KB_OIDC_ISSUER` 留空，
此时安全链会放开 dev 头，**不要带到试点环境**。`KB_DEV_SUBJECT_ENABLED=false` 时缺 issuer 会拒绝启动。

就绪探针：`GET https://<host>:8443/actuator/health/readiness`（含 db/redis/kafka/engine/storage）。
`engineProbe` 在 `KB_ENGINE_CAPABILITY_PROBE=true` 时会校验引擎 ACL 能力；WeKnora 返回
不支持租户隔离时，探针失败并 fail-close（`ENGINE_ACL_UNSUPPORTED`），不会带着错误范围上线。

## 7. 关键运行约束

- **OIDC 必填**：`KB_OIDC_ISSUER` 为空时非 dev 配置直接拒绝启动，禁止回落到 dev-subject 头。
- **拒答即零引用（#3）**：`NO_EVIDENCE` / `REFUSED` 只落回答与审计，不写 `chat_citation`；
  只有 `ANSWERED` 才写引用，且引用编号与 prompt 内编号一致。
- **ACL 版本（#5）**：知识库权限变更递增 `knowledge_base.acl_version`；缓存键带版本号，
  旧版本键永远读不到，失效时按指针精确删除，无需扫描。
- **退避（#18）**：失败任务进入 `RETRY_SCHEDULED` 并写 `next_run_at`，只有到期才可认领；
  `RetryPump` 用 `taskId+attempts` 生成确定性 eventId，重复通知被消费账本挡掉。
- **审计（#4）**：业务写与 `audit_event` 同事务；链哈希按写入字节计算，`metadata_json` 为
  `MEDIUMTEXT`（MySQL JSON 列会规范化文本，曾导致哈希校验失败）。外发走 outbox，Kafka 掉线只延迟外发。

## 8. 本地验证（测试基线 #16）

集成测试连真实 MySQL 13306 / Redis 16379 / Kafka 29092 / MinIO 19000（schema `kb_p1_test`）：

```bash
cd p1/portal
mvn -o clean test
```

测试凭据**不在代码里**：`KB_TEST_DDL_PASSWORD`/`KB_TEST_APP_PASSWORD`/`KB_TEST_REDIS_PASSWORD`/
`KB_MINIO_ACCESS_KEY`/`KB_MINIO_SECRET_KEY` 任一未设置时，`TestSecrets` 会读第 4 节的 secrets 文件
（目录可用 `KB_P1_SECRETS`/`KB_P0_SECRETS` 覆盖）；两者都缺则直接失败并指名要哪个变量或文件。

覆盖：Flyway V1–V11 全量迁移与 DDL/DML 账号隔离、业务写+审计同事务与回滚、哈希链校验、
退避到期认领与租约/心跳、RetryPump 幂等通知、ACL 版本失效、拒答零引用、WeKnora 适配器契约。
