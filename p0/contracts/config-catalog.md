# P0 配置目录（冻结）

三层同名对齐：**环境变量 → Spring 属性 → 代码里的 `@DefaultValue`**。
任何一方漂移都会让验证环境与代码不符，因此本表逐条写明默认值、影响面与 P1 归属。
凭据一律支持 `*_FILE` 形式（Docker secrets），见文末。

## 运行形态

- 宿主直跑（P0 默认）：`mvn spring-boot:run`，依赖 Compose 暴露的 127.0.0.1 端口。
- 容器内：`docker-compose.offline.yml`，`P0_*` 指向服务名（`mysql`/`redis`/`kafka`/`minio`），
  凭据只走 `*_FILE`。

## 数据源与迁移

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_DB_URL` | `spring.datasource.url` | `jdbc:mysql://127.0.0.1:13306/kb_p0?...serverTimezone=UTC...` | 库位置；强制 UTC 时区，DATETIME(3) 与 `Instant` 一致 |
| `P0_DB_USER` / `P0_DB_USER_FILE` | `spring.datasource.username` | `kb_p0_app` | **验证环境只有一个账号**：`MYSQL_USER` 由官方入口对该库授予 ALL（含 DDL），Flyway 迁移与运行期 DML 共用它。P1 必须拆成迁移账号（DDL）与运行时账号（仅 SELECT/INSERT/UPDATE/DELETE），见 P1 backlog |
| `P0_DB_PASSWORD` / `P0_DB_PASSWORD_FILE` | `spring.datasource.password` | 空 | 绝不打印；空值不会退化成匿名访问——容器健康检查与该账号都依赖它，密码错误时容器直接 not-ready |
| — | `spring.datasource.hikari.maximum-pool-size` | 12 | 与 `p0.ollama.max-concurrent` 一起决定并发上限，改一个必须看另一个 |
| — | `spring.datasource.hikari.connection-timeout` | 5000ms | 超时报 503 而不是排队雪崩 |
| — | `spring.flyway.enabled` / `locations` | true / `classpath:db/migration` | `baseline-on-migrate=false`、`out-of-order=false`：迁移顺序即事实，禁止补历史脚本 |
| — | `mybatis.configuration.arg-name-based-constructor-auto-mapping` | true | 结果映射到 record 构造器；关掉就全部拿到 null |
| — | `mybatis.configuration.default-statement-timeout` | 15s | 慢查询在驱动层截断，不拖死虚拟线程 |

## Redis（可重建缓存，永不放宽权限）

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_REDIS_HOST` / `P0_REDIS_PORT` | `spring.data.redis.host/port` | `127.0.0.1` / `16379` | — |
| `P0_REDIS_PASSWORD` / `P0_REDIS_PASSWORD_FILE` | `spring.data.redis.password` | 空 | 空密码只在验证环境允许 |
| — | `spring.data.redis.timeout` / `connect-timeout` | 1500ms | 超时即回源 MySQL，不重试、不报错给客户端 |
| — | `p0.cache.enabled` | true | 关掉后全部走 DB，用于对比验证缓存不改变判定结果 |
| — | `p0.cache.ttl-seconds` | 60 | 授权决策 TTL；水位线（watermark）比 TTL 更强的正确性保证 |
| — | `p0.cache.namespace` | `p0` | 键前缀 `p0:acl:{kbId}:{userId}`、`p0:aclwm:{kbId}`，与其他应用共用实例时的隔离边界 |

## Kafka

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_KAFKA_BOOTSTRAP`（容器）/ 宿主用 `localhost:29092` | `spring.kafka.bootstrap-servers` | `localhost:29092` | 见 `compose/.env` |
| `P0_INGEST_GROUP` | `p0.ingest.consumer-group` | `p0-ingest` | 入库组（requested+retry），监听端与 lag 读数同源 |
| `P0_DELETE_GROUP` | `p0.ingest.delete-consumer-group` | `p0-delete` | 删除组（delete 主题），与入库组分开计量 |
| `P0_INGEST_CONCURRENCY` | `spring.kafka.listener.concurrency` | 2 | 单实例消费线程数 |
| — | `spring.kafka.listener.ack-mode` | `manual_immediate` | **业务状态落库后才 ack**，见 `contracts/kafka/README.md` |
| — | `spring.kafka.consumer.enable-auto-commit` | false | 同上 |
| — | `spring.kafka.producer.acks` / `properties.enable.idempotence` | `all` / true | Outbox 投递不丢不重（重复由消费端账本挡） |
| — | `p0.ingest.topic-requested/-retry/-dlq/-delete` | 见 schema 目录 | broker 关掉 auto-create，主题必须先跑 `scripts/03-topics.sh` |
| — | `p0.ingest.max-attempts` / `backoff-base-ms` / `lease-seconds` | 5 / 2000 / 45 | 租约必须 > 单文档最长索引时间，否则同一任务被两个 worker 抢 |
| — | `p0.ingest.outbox-batch` / `outbox-interval-ms` | 50 / 500 | 积压恢复速度 |

## 对象存储（原件唯一权威）

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_MINIO_ENDPOINT` | `p0.storage.endpoint` | `http://127.0.0.1:19000` | — |
| `P0_MINIO_BUCKET` | `p0.storage.bucket` | `kb-p0-originals-private` | 私有桶，无匿名读，**不发预签名 URL** |
| `P0_MINIO_ACCESS_KEY(_FILE)` / `P0_MINIO_SECRET_KEY(_FILE)` | `p0.storage.access-key` / `secret-key` | 空 | `*_FILE` 优先（`SecretsEnvironmentPostProcessor` 注入 `addFirst`） |
| — | `p0.storage.retention-days` | 7 | 删除 Saga 的原件保留期 |

## 上传限制

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_UPLOAD_MAX_BYTES` | `p0.upload.max-bytes` **和** `spring.servlet.multipart.max-file-size` | 104857600（100MiB） | 两处必须同值，`max-request-size=115400000` 留 multipart 边框余量 |
| `P0_UPLOAD_MAX_CONCURRENT` | `p0.upload.max-concurrent` | 3 | 超了返回 429 `CONCURRENCY_LIMITED`，不落临时文件 |
| — | `p0.upload.allowed-extensions` | md,txt,csv,tsv,json,log,pdf,docx,xlsx,pptx | 白名单，非黑名单 |
| — | `p0.upload.allowed-mime-types` | 与扩展名一一对应 | 声明 MIME 与扩展名不符 → 422 `DOCUMENT_SIGNATURE_MISMATCH`；魔数再校验一次 |
| `P0_TEMP_DIR` | `p0.temp-dir` / `spring.servlet.multipart.location` | `../../.tmp` | 落盘暂存，用完即删；不在工作区外写 |

## 引擎与模型

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_ENGINE_PROVIDER` | `p0.engine.provider` | `stub` | `stub` 走内建 MySQL 索引引擎；`weknora` 走 `PendingWeKnoraAdapter`，**显式失败**不伪装成功（ADR-003） |
| `P0_ENGINE_BASE_URL` | `p0.engine.base-url` | `http://127.0.0.1:18180/__engine-stub__` | 仅标识，stub 为进程内实现 |
| `P0_ENGINE_TIMEOUT_MS` | `p0.engine.timeout-ms` | 60000 | 超时 → `ENGINE_TIMEOUT` → 重试主题 |
| — | `p0.engine.chunk-size` / `chunk-overlap` | 800 / 120 | `Chunker` 滑窗，重叠不得超 `chunk-size/2` |
| `P0_SCORE_THRESHOLD` | `p0.engine.score-threshold` | `0.18` | top-k 是**超取数**（复核后再截断），`context-chunks` 才是进 Prompt 的上限；阈值挡不住语料外问题（见 ADR-008），P1 需在生产语料上重校准 |
| `P0_EMBEDDING_MODE` | `p0.engine.embedding-mode` | `ollama` | `deterministic` 为无模型降级（哈希向量），仅供断网冒烟，**不得用于质量基线** |
| `P0_CHAT_MODE` | `p0.engine.chat-mode` | `ollama` | `extractive` 抽取式降级，同上限制 |
| `P0_OLLAMA_BASE_URL` | `p0.ollama.base-url` | `http://127.0.0.1:11434` | 本机推理，断网可用性的关键 |
| `P0_OLLAMA_CHAT_MODEL` / `P0_OLLAMA_EMBED_MODEL` | `p0.ollama.chat-model` / `embed-model` | `deepseek-r1:8b` / `nomic-embed-text` | 权重必须预先在位；缺失即 `MODEL_UNAVAILABLE`，**不回退云端** |
| `P0_OLLAMA_ALLOW` | `p0.ollama.allow-remote` | true | 关掉后拒绝任何非回环模型地址，离线核查用 |
| — | `p0.ollama.timeout-ms` / `max-concurrent` | 180000 / 5 | SSE 超时 = 该值 + 30s |
| `P0_MODEL_MAX_PROMPT_CHARS` | `p0.ollama.max-prompt-chars` | 4096 | 证据预算：按 score 从高到低贪心填充，第一条装不下即停止（引用连续断号即由此保证），不会跳着拼证据 |
| `P0_MODEL_MAX_NEW_TOKENS` / — | `p0.ollama.max-new-tokens` / `temperature` | 800 / 0.1 | 低温度保证引用可核对 |
| `P0_ENGINE_LEAK` | `p0.engine.leak-out-of-scope` | false | **仅验证用**：让引擎故意返回越界 chunk，证明复核挡住（越权测试矩阵的一条） |
| `P0_ENGINE_TOKEN_FILE` | `p0.engine.token` | 空 | **P0 无消费方**（stub 引擎在进程内、无鉴权）。字段保留是给 WeKnora 的，P1 接入前不要把这条当成"已生效的鉴权配置" |

## 身份与验证夹具

| 环境变量 | 属性 | 默认值 | 影响 |
|---|---|---|---|
| `P0_AUTH_STUB_ENABLED` | `p0.auth.stub-enabled` | true | 允许 `X-P0-Subject: <tenant>:<user>` 测试身份头；P1 必须 false（OIDC 取代，授权代码不变） |
| `P0_FIXTURE_ENABLED` | `p0.fixture.enabled` | **false** | false 时夹具控制器**根本不注册**（`@ConditionalOnProperty`），不是"注册了再拦" |
| `P0_FIXTURE_TOKEN` / `P0_FIXTURE_TOKEN_FILE` | `p0.fixture.token` | 空 | 即使 enabled 也必须另带 `X-P0-Fixture-Token`；常量为空 → 一律 403（fail-close）；比较用 `MessageDigest.isEqual` |
| `P0_FIXTURE_BASELINE_TENANT` / `_ADMIN` | `p0.fixture.baseline-tenant/-admin` | `red` / `admin` | 空库时引导出一个基线租户与管理员，否则第一步就无法验证 |
| `P0_CHAT_RATE_PER_MIN` | `p0.chat.rate-per-minute` | 30 | 每主体每分钟；429 带真实 `Retry-After`（到窗口结束的秒数） |
| `P0_OFFLINE_ENFORCED` | `p0.offline-enforced` | false | true 时禁止任何非回环出站地址，断网验证用 |
| `P0_SECRETS_DIR` | `p0.secrets-dir` | `../compose/secrets` | `00-gen-secrets.sh` 生成，权限靠文件系统，不入库不入镜像 |
| `P0_APP_PORT` | `server.port` | **18180** | 原为 18080，验证机上该端口已被无关服务占用，故整体改用 18180（`compose/.env`、openapi `servers` 同步） |

## 凭据解析规则

`SecretsEnvironmentPostProcessor` 把 7 个 `*_FILE` 环境变量（`P0_DB_USER_FILE`、
`P0_DB_PASSWORD_FILE`、`P0_REDIS_PASSWORD_FILE`、`P0_MINIO_ACCESS_KEY_FILE`、
`P0_MINIO_SECRET_KEY_FILE`、`P0_ENGINE_TOKEN_FILE`、`P0_FIXTURE_TOKEN_FILE`）
读成对应属性并以 `addFirst` 覆盖内联值；文件内容 `strip()`（含 `\r`），
读不到文件**直接启动失败**，不回退空凭据（避免"配置错了但看着在跑"）。

## 可观测性

- `management.endpoints.web.exposure.include` = `health,info,metrics`；`show-details: never`
  （健康详情不暴露连接串）。
- readiness 组 = `db,redis,kafkaProbe,diskSpace,engineProbe,storageProbe`：任一依赖缺失即 not-ready，
  原件存储（MinIO）也在其中——它是唯一权威副本，缺它却报"就绪"是假的。
  `kafkaProbe` 是自己写的：Boot 3.5 不再自带 kafka 健康指示器，而"能不能收事件"是入库链路的前提。
  探针只回答可达性，**不代表积压已清空**（积压看 `GET /api/v1/verification/backlog`）。
  与"离线可用"不矛盾：离线冷启动要求的是**本机依赖齐全**，不是允许云端兜底。
- 日志 pattern 带 `%X{traceId},%X{actor}`；`server.error.include-*` 全部关闭，堆栈只进日志。
