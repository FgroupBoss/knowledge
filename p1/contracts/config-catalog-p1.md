# P1/P2 配置目录（冻结）

pilot 侧（`com.acme.kb.p1`）配置项的唯一事实源。关闭 `p0/decisions/P1-backlog.md` 第 22 项
（P1 的 `KB_*` 变量不在三层同名核对内）。P0 的 `P0_*` 命名域见 `p0/contracts/config-catalog.md`，
本文件**只覆盖 `KB_*`**，两套命名不混用、不互相替换。

机器校验：`python p1/scripts/24-check-contracts-p1.py`。对 `kb.*` 属性逐条核对三层：

| 层 | 来源 | 核对内容 |
|---|---|---|
| 环境变量 | `p1/portal/src/main/resources/application.yml` 的 `${KB_*:default}`；凭据另有 `*_FILE` 映射（`SecretsEnvironmentPostProcessor`） | 变量名必须出现在本文件「环境变量」列 |
| Spring 属性 | 同上 `kb:` 子树；代码侧 `KbProperties` 嵌套 record 字段名（camelCase → kebab-case） | 两处必须同时存在且与本文件「属性」列一致 |
| 默认值 | `@DefaultValue("…")` 与 yml 内联 `${X:…}` | 两者不符 → ERROR；本文件「默认值」列与代码不符 → ERROR |

另外两条同样会报错：`p1/compose/docker-compose.yml` 里设置了未登记的 `KB_*`；
登记的属性在 yml 或 `KbProperties` 里被删掉。

`（空）` = 默认空串，值只能由 env 或 `*_FILE` 提供；读取处一律 `strip()`（CRLF 会让 MinIO 签名多一个 `\r`）。
`（yml 固定）` = 无 `@DefaultValue`，取值写在 `application.yml` 里（清单类属性）。

## 运行形态

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_ROLE` | `kb.role` | `api` | `api` 只起 HTTP；`worker` 才装配 Kafka 监听、退避泵、租约心跳、Wiki 调度。角色装错会让 api 与 worker 抢同一分区 |
| `KB_SECRETS_DIR` | `kb.secrets-dir` | `../compose/secrets` | `*_FILE` 相对路径的基准目录 |
| `KB_TEMP_DIR` | `kb.temp-dir` | `../../.tmp` | 上传暂存目录；容器内为 `/tmp`（`tmpfs`） |
| `KB_OFFLINE_ENFORCED` | `kb.offline-enforced` | `false` | true 时禁止任何非内网出口，且与 `KB_DEV_SUBJECT_ENABLED=true` 互斥（同时开则拒绝启动） |
| `KB_APP_PORT` | `server.port` | `18181` | api 监听端口；容器内经网关 8443 访问 |

## 数据源与账号拆分（#6）

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_DB_URL` | `spring.datasource.url` | `jdbc:mysql://127.0.0.1:13306/kb_p1?...serverTimezone=UTC...connectionTimeZone=%2B00:00` | 强制 UTC，`DATETIME(3)` 与 `Instant` 一致。容器内必须写 `mysql:3306`，写宿主映射端口是已发生过的缺陷 |
| `KB_DB_USER` / `KB_DB_USER_FILE` | `spring.datasource.username` | `kb_p1_app` | 运行账号只有 DML；`*_FILE` 优先由 `SecretsEnvironmentPostProcessor` 解析 |
| `KB_DB_PASSWORD` / `KB_DB_PASSWORD_FILE` | `spring.datasource.password` | （空） | 绝不打印；空口令不退化为匿名 |
| `KB_DB_DDL_USER` | `spring.flyway.user` | `kb_p1_ddl` | 迁移账号，持 DDL |
| `KB_DB_DDL_PASSWORD` / `KB_DB_DDL_PASSWORD_FILE` | `spring.flyway.password` | （空） | 只在启动迁移时使用 |
| `KB_DB_ENFORCE_LEAST_PRIVILEGE` | `kb.database.enforce-least-privilege` | `true` | `DatabasePrivilegeGuard` 在 `ApplicationReadyEvent` 执行 `SHOW GRANTS`，运行账号带 CREATE/DROP/ALTER/GRANT 即拒绝启动 |
| — | `spring.flyway.baseline-on-migrate` | `false` | 迁移顺序即事实，禁止补历史脚本 |
| — | `spring.flyway.out-of-order` | `false` | 同上 |
| — | `spring.flyway.locations` | `classpath:db/migration` | 执行源；交付评审副本见 `p1/db/migration/` |
| — | `mybatis.configuration.map-underscore-to-camel-case` | `true` | 配合显式 `AS` 驼峰别名使用 |
| — | `mybatis.configuration.arg-name-based-constructor-auto-mapping` | `true` | 关掉则 record 全拿到 null |
| — | `mybatis.configuration.default-statement-timeout` | `15`（秒） | 慢查询在驱动层截断 |
| — | `spring.datasource.hikari.maximum-pool-size` | `12` | 与 `KB_MODEL_MAX_CONCURRENT`、`KB_WIKI_SYNC_CONCURRENCY` 共同决定实际并发 |

## Redis（可重建加速件，永不放宽权限）

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_REDIS_HOST` | `spring.data.redis.host` | `127.0.0.1` | 容器内为 `redis` |
| `KB_REDIS_PORT` | `spring.data.redis.port` | `16379` | **容器内必须 6379**，16379 只是 P0 在宿主机的映射 |
| `KB_REDIS_PASSWORD` / `KB_REDIS_PASSWORD_FILE` | `spring.data.redis.password` | （空） | 复用 P0 口令文件 |
| — | `spring.data.redis.timeout` / `connect-timeout` | `1500ms` | 超时即回源 MySQL，不报错给客户端 |

## Kafka 与入库管道

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_KAFKA_BOOTSTRAP` | `spring.kafka.bootstrap-servers` | `localhost:29092` | 容器内 `kafka:9092`；broker 关 auto-create，主题必须先建 |
| `KB_INGEST_CONCURRENCY` | `spring.kafka.listener.concurrency` | `2` | 单实例消费线程数 |
| — | `spring.kafka.listener.ack-mode` | `manual_immediate` | 业务状态落库后才 ack |
| — | `spring.kafka.consumer.enable-auto-commit` | `false` | 同上；offset 不是业务状态 |
| — | `spring.kafka.listener.missing-topics-fatal` | `false` | 缺主题时仍能启动，但 outbox 会积压（`kb.audit.v1` 缺失会拖慢在线审计写） |
| — | `kb.ingest.topic-requested` | `kb.ingest.requested.v1` | 无 env：写死在 yml，与 schema 同名 |
| — | `kb.ingest.topic-retry` | `kb.ingest.retry.v1` | 同上 |
| — | `kb.ingest.topic-dlq` | `kb.ingest.dlq.v1` | 同上 |
| — | `kb.ingest.topic-delete` | `kb.document.delete.v1` | 同上 |
| — | `kb.ingest.topic-reconcile` | `kb.ingest.reconcile.v1` | **孤儿配置**，backlog #21 处理，本表不假装已接线 |
| `KB_INGEST_GROUP` | `kb.ingest.consumer-group` | `p1-ingest` | 订阅 requested+retry |
| `KB_DELETE_GROUP` | `kb.ingest.delete-consumer-group` | `p1-delete` | 只订阅 delete |
| `KB_INGEST_MAX_ATTEMPTS` | `kb.ingest.max-attempts` | `5` | 超限进 DLQ |
| `KB_INGEST_BACKOFF_BASE_MS` | `kb.ingest.backoff-base-ms` | `2000` | `base × 2^attempts` |
| `KB_INGEST_BACKOFF_MAX_MS` | `kb.ingest.backoff-max-ms` | `300000` | 退避封顶 |
| `KB_INGEST_LEASE_SECONDS` | `kb.ingest.lease-seconds` | `45` | 必须 > 单文档最长索引时间，否则同一任务被两个 worker 抢 |
| `KB_INGEST_LEASE_HEARTBEAT` | `kb.ingest.heartbeat-seconds` | `15` | 心跳间隔；env 名沿用 P1 既有拼写（属性名是 `heartbeat-seconds`，**不同名是已登记的历史项**） |
| `KB_INGEST_RETRY_PUMP_MS` | `kb.ingest.retry-pump-ms` | `500` | 退避泵扫描周期；到期认领靠 `next_run_at`，不靠 sleep |
| — | `kb.ingest.outbox-batch` | `50` | 每批领取未发布行数 |
| — | `kb.ingest.outbox-interval-ms` | `500` | 发布周期 |

## 上传与对象存储（原件权威在 MinIO）

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_UPLOAD_MAX_BYTES` | `kb.upload.max-bytes` | `104857600` | 同时喂 `spring.servlet.multipart.max-file-size`；超限 `DOCUMENT_TOO_LARGE`(413) |
| `KB_UPLOAD_MAX_CONCURRENT` | `kb.upload.max-concurrent` | `3` | 并发槽满 → `CONCURRENCY_LIMITED`(429) |
| — | `kb.upload.allowed-extensions` | （yml 固定） | 不在清单 → `DOCUMENT_FORMAT_UNSUPPORTED` |
| — | `kb.upload.allowed-mime-types` | （yml 固定） | MIME/魔数不符 → `DOCUMENT_SIGNATURE_MISMATCH` |
| `KB_MINIO_BUCKET` | `kb.storage.bucket` | `kb-p1-originals-private` | 私有桶，不对外暴露 |
| `KB_MINIO_ENDPOINT` | `kb.storage.endpoint` | `http://127.0.0.1:19000` | 容器内 `http://minio:9000` |
| `KB_MINIO_ACCESS_KEY` / `KB_MINIO_ACCESS_KEY_FILE` | `kb.storage.access-key` | （空） | 复用 P0 凭据文件 |
| `KB_MINIO_SECRET_KEY` / `KB_MINIO_SECRET_KEY_FILE` | `kb.storage.secret-key` | （空） | 同上 |
| `KB_STORAGE_RETENTION_DAYS` | `kb.storage.retention-days` | `30` | 原件保留期 |
| `KB_TEMP_DIR` | `spring.servlet.multipart.location` | `../../.tmp` | 与 `kb.temp-dir` 同源变量 |

## 引擎与模型

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_ENGINE_PROVIDER` | `kb.engine.provider` | `stub` | `stub` 只允许 test/dev profile；pilot 必须 `weknora`（F-1 卡点：镜像获取受加速站限制） |
| `KB_ENGINE_BASE_URL` | `kb.engine.base-url` | `http://127.0.0.1:18180/__engine-stub__` | 容器内指向真引擎服务 |
| `KB_ENGINE_TIMEOUT_MS` | `kb.engine.timeout-ms` | `60000` | 超时 → `ENGINE_TIMEOUT`（暂时） |
| — | `kb.engine.chunk-size` | `800` | Wiki 发布沿用同一分片器 |
| — | `kb.engine.chunk-overlap` | `120` | 同上 |
| `KB_TOP_K` | `kb.engine.top-k` | `20` | 候选数 |
| `KB_CONTEXT_CHUNKS` | `kb.engine.context-chunks` | `6` | 进 prompt 的片段数 |
| `KB_SCORE_THRESHOLD` | `kb.engine.score-threshold` | `0.18` | 运行时以 `calibration.json` 为准（ADR-008） |
| — | `kb.engine.leak-out-of-scope` | `false` | 置 true 仅供取证，pilot 禁用 |
| `KB_ENGINE_CAPABILITY_PROBE` | `kb.engine.capability-probe` | `true` | 引擎不支持租户隔离 → readiness `engineProbe` DOWN（`ENGINE_ACL_UNSUPPORTED`，启动期 fail-close，不作为 API 码） |
| `KB_EMBEDDING_MODE` | `kb.engine.embedding-mode` | `ollama` | `deterministic` 仅离线验证 |
| `KB_CHAT_MODE` | `kb.engine.chat-mode` | `ollama` | `extractive` 仅离线验证 |
| — | `kb.engine.fallback-embedding-dim` | `256` | 确定性向量维度 |
| `KB_ENGINE_TOKEN` / `KB_ENGINE_TOKEN_FILE` | `kb.engine.token` | （空） | 引擎凭据 |
| `KB_WEKNORA_KB_PATH` | `kb.engine.weknora-kb-path` | `/api/v1/knowledge-bases` | 见 `p1/contracts/weknora-adapter.md` |
| `KB_WEKNORA_SEARCH_PATH` | `kb.engine.weknora-search-path` | `/api/v1/search` | 同上 |
| `KB_OLLAMA_BASE_URL` | `kb.ollama.base-url` | `http://127.0.0.1:11434` | 容器内 `host.docker.internal:11434` |
| `KB_OLLAMA_CHAT_MODEL` | `kb.ollama.chat-model` | `deepseek-r1:8b` | 必须按摘要固定并启动校验（#19） |
| `KB_OLLAMA_EMBED_MODEL` | `kb.ollama.embed-model` | `nomic-embed-text` | 同上 |
| `KB_OLLAMA_ALLOW` | `kb.ollama.allow-remote` | `true` | false 时只允许内网地址 |
| — | `kb.ollama.timeout-ms` | `180000` | 长答案不被切 |
| `KB_MODEL_MAX_CONCURRENT` | `kb.ollama.max-concurrent` | `5` | 在线问答信号量 |
| `KB_MODEL_MAX_PROMPT_CHARS` | `kb.ollama.max-prompt-chars` | `4096` | 引用指标必须注明该口径 |
| — | `kb.ollama.max-new-tokens` | `800` | — |
| — | `kb.ollama.temperature` | `0.1` | — |
| `KB_MODEL_DIGEST_VERIFY` | `kb.ollama.digest-verify` | `true` | 摘要不符 → 拒绝启动 |

## 缓存 / 问答 / 会话

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| — | `kb.cache.enabled` | `true` | 关掉全部回源 DB，用于证明缓存不改变判定结果 |
| `KB_ACL_CACHE_TTL` | `kb.cache.ttl-seconds` | `60` | 授权决策 TTL |
| `KB_ACL_CACHE_VERSION_TTL` | `kb.cache.version-ttl-seconds` | `300` | 版本水位线 TTL |
| — | `kb.cache.namespace` | `p1` | 键形如 `p1:acl:{kbId}:{userId}:v{n}`；ACL 变更递增 `acl_version` 后旧键永远读不到 |
| `KB_CHAT_RATE_PER_MIN` | `kb.chat.rate-per-minute` | `30` | 固定分钟窗口 → 429 带真实 `Retry-After` |
| `KB_CHAT_RATE_PER_MIN_TENANT` | `kb.chat.tenant-per-minute` | `300` | 租户维度限流 |
| `KB_SCORE_THRESHOLD_FILE` | `kb.chat.calibration-file` | `calibration.json` | 拒答阈值来源（ADR-008） |
| `KB_DEV_SUBJECT_ENABLED` | `kb.auth.dev-subject-enabled` | `false` | true 才接受 `X-KB-Subject` 测试头并关闭 CSRF；**不得进试点镜像**；与缺 issuer 互斥 |
| `KB_SESSION_COOKIE` | `server.servlet.session.cookie.name` | `kb_session` | `HttpOnly` + `Secure`（8443 才有） |
| `KB_COOKIE_SECURE` | `server.servlet.session.cookie.secure` | `false`（compose 里为 `true`） | 试点必须 true，否则会话可被明文端口带走 |
| `KB_SESSION_TIMEOUT` | `kb.session.timeout` / `spring.session.timeout` | `8h` | 空闲超时 |
| `KB_SESSION_ABSOLUTE` | `kb.session.absolute-timeout` | `12h` | 绝对超时，换 sessionId 时重新计时 |
| — | `kb.session.cookie-name` | `__Host-kb_session` | 应用内校验用名（与 `server.servlet...cookie.name` 不同层，改一个要看另一个） |
| — | `kb.session.csrf-cookie` | `XSRF-TOKEN` | Cookie-to-header |
| — | `kb.session.csrf-header` | `X-XSRF-TOKEN` | 缺失/不符 → 403 `CSRF_INVALID` |

## 身份与审计

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_OIDC_ISSUER` | `kb.security.oidc-issuer` | （空） | 非 dev 模式为空 → **拒绝启动**；发现文档取不到也拒绝启动 |
| `KB_OIDC_CLIENT_ID` | `kb.security.oidc-client-id` | （空） | — |
| `KB_OIDC_CLIENT_SECRET` / `KB_OIDC_CLIENT_SECRET_FILE` | `kb.security.oidc-client-secret` | （空） | 占位值必须替换（`p1/compose/README.md` 第 4 节） |
| `KB_OIDC_REDIRECT_URI` | `kb.security.oidc-redirect-uri` | （空） | 必须与 IdP 登记一致 |
| `KB_OIDC_SCOPES` | `kb.security.oidc-scopes` | `openid,profile,email` | — |
| `KB_OIDC_JIT_ENABLED` | `kb.security.jit-enabled` | `false` | true 才按登录主体自动建用户 |
| `KB_AUDIT_RETENTION_DAYS` | `kb.audit.retention-days` | `180` | 审计保留期 |
| `KB_AUDIT_CHAIN_ENABLED` | `kb.audit.chain-enabled` | `true` | 链哈希按写入字节计算；`metadata_json` 为 `MEDIUMTEXT`（JSON 列规范化曾致校验失败） |
| `KB_AUDIT_TOPIC` | `kb.audit.topic` | `kb.audit.v1` | 需在 `p0/contracts/kafka/README.md` 登记且**必须建题** |

## Wiki 治理（P2 新增）

§6.4 的 11 项 + 接线所需的 topic/group 共 18 项。**三层同名以本表为准**：
设计文档 D-12 散文里写的 `kb.wiki.sync-max-concurrent` 是同一项的旧名，裁定统一为
`kb.wiki.sync-concurrency`（契约文件 > 散文，登记见 `p0/decisions/ADR-010-P2契约事实源迁到p1-contracts.md`）。

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_WIKI_ENABLED` | `kb.wiki.enabled` | `false` | 关闭时不注册 Wiki 路由与调度器（默认关，未验收的功能不进产物） |
| `KB_WIKI_MAX_BODY_BYTES` | `kb.wiki.max-body-bytes` | `204800` | 正文上限（200 KiB）；超限 `WIKI_BODY_TOO_LARGE`(413) |
| `KB_WIKI_SYNC_CONCURRENCY` | `kb.wiki.sync-concurrency` | `2` | 发布同步独立闸（D-12），与 `kb.ollama.max-concurrent` 分账，发布不挤占问答 |
| `KB_WIKI_BACKOFF_BASE_MS` | `kb.wiki.backoff-base-ms` | `5000` | `base × 2^attempts`，只作用于 `SYNC` 轴暂时性失败 |
| `KB_WIKI_BACKOFF_MAX_MS` | `kb.wiki.backoff-max-ms` | `600000` | 退避封顶 |
| `KB_WIKI_MAX_ATTEMPTS` | `kb.wiki.max-attempts` | `6` | 超限 → `DEAD_LETTERED` + `kb.wiki.dlq.v1` |
| `KB_WIKI_LEASE_SECONDS` | `kb.wiki.lease-seconds` | `45` | 沿用 P1 租约参数值；必须 > 单页最长索引时间 |
| `KB_WIKI_LEASE_HEARTBEAT` | `kb.wiki.heartbeat-seconds` | `15` | 心跳间隔（env 名与属性名不同名，照 `KB_INGEST_LEASE_HEARTBEAT` 先例） |
| `KB_WIKI_STALE_DAYS` | `kb.wiki.stale-days` | `14` | 超过则 `freshness = STALE`；**不改 status**（D-02） |
| `KB_WIKI_EXPIRE_AFTER_DAYS` | `kb.wiki.expire-after-days` | `0` | 0 = 不自动置 `EXPIRED`，只提醒 |
| `KB_WIKI_REVIEW_SLA_HOURS` | `kb.wiki.review-sla-hours` | `72` | 审核超时提醒阈值 |
| `KB_WIKI_REMINDER_CRON` | `kb.wiki.reminder-cron` | `0 30 2 * * *` | 表驱动扫描（低峰），不是进程内 timer；重启不丢，靠 `next_run_at <= now` 认领 |
| `KB_WIKI_RETENTION_DAYS` | `kb.wiki.retention-days` | `180` | 修订历史在线保留 |
| — | `kb.wiki.topic-sync` | `kb.wiki.sync.v1` | 发布同步主题（W2 接线，schema 已冻结） |
| — | `kb.wiki.topic-retract` | `kb.wiki.retract.v1` | 撤稿主题，独立消费者组（D-04） |
| — | `kb.wiki.topic-dlq` | `kb.wiki.dlq.v1` | 死信信封 |
| `KB_WIKI_SYNC_GROUP` | `kb.wiki.consumer-group` | `p1-wiki-sync` | 只订阅 sync |
| `KB_WIKI_RETRACT_GROUP` | `kb.wiki.retract-consumer-group` | `p1-wiki-retract` | 只订阅 retract；与 sync 分组，避免一个组积压阻塞另一个 |

## compose 级变量（不进 Spring，只影响编排）

这三项没有对应的 Spring 属性，脚本按「无属性行」处理：登记即可，不要求 `application.yml` 里出现。

| 环境变量 | Spring 属性 | 默认值 | 影响 |
|---|---|---|---|
| `KB_P0_SECRETS` | — | `../../p0/compose/secrets` | 复用 P0 的口令文件目录（Redis/MinIO）；改成别的目录不会自动建口令 |
| `KB_INFRA_NETWORK` | — | `kb-p0_p0-backend` | **internal** 网络，容器无外网路由（AGENTS.md §9 网络两层） |
| `KB_INGRESS_NETWORK` | — | `kb-p0_p0-ingress` | 只有 api/gateway 挂它才发布端口；只挂 internal 时写 `ports:` 不生效 |

## 凭据注入规则（所有阶段一致）

- 凭据**只**从环境变量或 `*_FILE` 进入进程，映射表在 `SecretsEnvironmentPostProcessor`：
  `KB_DB_USER_FILE`→`spring.datasource.username`、`KB_DB_PASSWORD_FILE`→`spring.datasource.password`、
  `KB_DB_DDL_PASSWORD_FILE`→`spring.flyway.password`、`KB_REDIS_PASSWORD_FILE`→`spring.data.redis.password`、
  `KB_MINIO_ACCESS_KEY_FILE`→`kb.storage.access-key`、`KB_MINIO_SECRET_KEY_FILE`→`kb.storage.secret-key`、
  `KB_ENGINE_TOKEN_FILE`→`kb.engine.token`、`KB_OIDC_CLIENT_SECRET_FILE`→`kb.security.oidc-client-secret`。
  本表把这些行拆在各组里，脚本按整体集合核对：**新增 `*_FILE` 映射而不登记 → ERROR**。
- 内联值优先于 `*_FILE`；两者都缺 → 空串，随后由 fail-close 判定（缺 OIDC issuer、缺口令一律拒绝启动）。
- 脚本与文档**绝不打印**任何取值；`p1/compose/secrets/` 已被 `.gitignore` 排除。
