# Kafka 主题与投递语义（冻结）

Schema 与本目录 `*.schema.json` 一一对应，机器校验见 `../error-codes.md` 提到的
`scripts/04-check-contracts.py`（含样例载荷与 Java 事件字段双向核对）。

## 主题清单

创建脚本 `scripts/03-topics.sh`（**禁用 broker auto-create**：主题名写错必须当场失败，
不能让"看起来在跑"的空主题骗过验证）。

| 主题 | 分区 | 键 | 保留 | 用途 |
|---|---|---|---|---|
| `kb.ingest.requested.v1` | 3 | `documentId` | 3d | 首次入库与 retry 重新入队 |
| `kb.ingest.retry.v1` | 3 | `documentId` | 3d | 退避重试（仅暂时性错误） |
| `kb.ingest.dlq.v1` | 1 | `documentId` | 3d | 死信信封，只经审计入口重放 |
| `kb.document.delete.v1` | 3 | `documentId` | 3d | 删除 Saga |

按 `documentId` 分区 ⇒ 同一文档的事件严格有序，不同文档并行。
`replication-factor=1 / min.insync.replicas=1` 是 P0 单机验证环境的取值，
P1 生产必须 `3/2`（记入 P1 backlog，不在 P0 假装高可用）。

### P1/P2 增量主题

| 主题 | 状态 | 生产方 | 分区键 | 建题位置 |
|---|---|---|---|---|
| `kb.audit.v1` | 已接线 | `AuditWriter.enqueueExport()` 事务内写 outbox，由发布器投递 | `tenantId` | `p1/compose/README.md` 第 2 节手工建题 |
| `kb.ingest.reconcile.v1` | **未接线** | 无：只有 `KbProperties.Ingest.topicReconcile` 与 `application.yml` 的配置项，没有生产方也没有消费方 | — | 无处建题 |
| `kb.wiki.sync.v1` | **schema 已冻结，生产方未接线**（P2-W2） | Outbox（approve 事务写 `wiki_sync_task` 意图，发布器属 W2） | `pageId` | `p1/compose/README.md` 第 2 节 + `scripts/03-topics.sh` |
| `kb.wiki.retract.v1` | **schema 已冻结，生产方未接线**（P2-W2） | Outbox（retract 事务同上），独立消费者组 `p1-wiki-retract` | `pageId` | 同上 |
| `kb.wiki.dlq.v1` | **schema 已冻结，生产方未接线**（P2-W2） | 同步任务超 `kb.wiki.max-attempts` 时写死信信封 | `pageId` | 同上 |

`kb.audit.v1` 载荷字段（顺序即序列化顺序，取自 `AuditWriter`）：
`auditId, tenantId, actorId, action, resourceType, resourceId, result, reasonCode, traceId, seq,
recordHash, createdAt`。`recordHash` 是链上哈希，消费端可据此独立校验链自洽；
载荷**不含文档正文、Prompt、对象键与凭据**。`tenantId` 为 null 的登录失败事件不进外发通道。
序列化受 `default-property-inclusion: non_null` 影响：**值为 null 的键不出现在载荷里**，
所以 schema 里除 `auditId/tenantId/action/result/createdAt` 外全部为可选。

Wiki 三个主题的载荷同样**不含正文**：消费者回查 MySQL 取正文并核对 `bodyDigest`
（D-01「权威在行里、索引是派生物」的直接后果）。

机器校验分两层，别再混为一谈：

- `python p0/scripts/04-check-contracts.py`：本目录全部 `*.schema.json` 与 `samples/*.sample.json`
  一致性 + P0 侧 `com.acme.kb.p0.pipeline.Events` 字段 ↔ schema（**不覆盖 P1/P2 的 Java 事件类**）。
- `python p1/scripts/24-check-contracts-p1.py`：pilot 侧契约漂移检查，含
  `AuditWriter` 载荷键 ↔ `audit-event.v1.schema.json`、Wiki 事件字段 ↔ 三个 wiki schema、
  错误码 ↔ 码表 ↔ OpenAPI、`KbProperties` ↔ 配置目录、Controller ↔ OpenAPI 路径、
  `WikiStateMachine` 边表 ↔ `p1/contracts/state-machine-wiki.md`。

header 沿用 `p0-*` 命名（`TraceIds.KAFKA_HEADER_EVENT_ID` 等常量未随阶段改名），
读侧仍按"空值不写 header"处理。

登记见 `P1-backlog.md` 第 20 项（补 schema 与样例、把校验接到 `04-check-contracts.py`）
与第 21 项（reconcile 主题要么接线要么删配置）。
**孤儿主题教训**：三个 wiki 主题现在只有 schema、没有生产方，属已登记的过渡状态
（见 `P1-backlog.md` 的 Wiki 契约补齐项与 `p1/contracts/state-machine-wiki.md` §6）；
W2 接线时若仍无生产方，必须删配置而不是留空主题。

## 生产者

`acks=all`、`enable.idempotence=true`、`retries=100`、`max.in.flight=5`、
`delivery.timeout.ms=15000`、`request.timeout.ms=5000`、`linger.ms=5`。

事件**只由事务 Outbox 产生**（`outbox_event` 与业务写库同事务），不存在"业务已提交但事件丢失"：
发布器用 `FOR UPDATE SKIP LOCKED` 领取未发布行，`send().get(10s)` 成功后才置 `published_at`
并把文档 `UPLOADED→QUEUED`。发布成功但标记失败 ⇒ 重复投递，由消费端 `eventId` 账本挡住。

## Kafka Header

| header | 来源 | 说明 |
|---|---|---|
| `p0-event-id` | `outbox_event.event_id` | 与 payload 的 `eventId`（死信为 `dlqId`）一致，消费端幂等键 |
| `p0-schema-version` | `outbox_event.schema_version` | 当前恒为 1 |
| `p0-trace-id` | 产生事件线程的 MDC | 与 HTTP `X-Trace-Id` 同值，API→任务→worker→引擎→审计一条链 |
| `p0-correlation-id` | payload `correlationId` | 跨重试稳定的关联 ID |
| `p0-actor-id` | 上传/删除的发起主体 | 仅 requested/delete 事件有；重试与死信不带（不是新的用户动作） |

空值**不写 header**：读侧 `header == null` 才代表"确实没有上下文"，空串会被误判成有。

## 消费者

- 两个独立消费组：`p0-ingest`（`P0_INGEST_GROUP`，订阅 requested+retry）与
  `p0-delete`（`P0_DELETE_GROUP`，只订阅 delete）。组名由 `p0.ingest.consumer-group` /
  `p0.ingest.delete-consumer-group` 单点定义，监听端与 `/verification/backlog` 的 lag 读数共用，
  避免"查错组得到假积压"。`CooperativeStickyAssignor`，`max-poll-records=16`，`session.timeout.ms=15000`。
- `enable-auto-commit=false` + `ack-mode=manual_immediate`：**登记消费账本 → 租约认领 → 执行 →
  业务状态落库 → 才 ack**。Kafka offset 不是业务状态，崩溃后重复投递由
  `event_consumption` 主键与 `ingest_task` 租约双重挡住。
- 毒消息（payload 连 JSON 都解析不出、或缺 `eventId`/`operationId`）：ack 以免卡死分区，
  记 ERROR 日志含 topic/partition/offset；该文档停在上传态，由积压读数的
  `unconvergedDocuments` 暴露，绝不静默丢弃。

## 重试与死信

退避 `p0.ingest.backoff-base-ms * 2^attempts`（默认 2s 起，封顶 `2^6` 倍），
上限 `p0.ingest.max-attempts=5`；`notBefore` 之后的消息才处理。错误分类与
`retryReason` 归类的映射见 `../error-codes.md` 与 `Events.retryReason()`。
删除 Saga 的失败**不进** `kb.ingest.retry.v1`（见 `../state-machine.md` 规则 5）。

DLQ 重放不是"重新塞回主题"：必须先用 `dlqId`/`correlationId` 审计，
把 `replayState` 从 `UNREVIEWED` 推进后才允许限重放。P0 只做审计与取证读数，
不提供自动重放端点（避免误放造成越权索引），P1 再补受控重放工单流。

## Wiki 同步与撤稿（P2，schema 冻结、生产方待 W2）

- 退避参数与入库管道**分账**：`kb.wiki.backoff-base-ms`(5000) × 2^attempts，封顶
  `kb.wiki.backoff-max-ms`(600000)，上限 `kb.wiki.max-attempts`(6)；认领条件仍是
  `next_run_at <= now`，不靠消费者 sleep。
- 消费组两个且互相隔离：`kb.wiki.sync.v1` → `p1-wiki-sync`，`kb.wiki.retract.v1` →
  `p1-wiki-retract`。分组的理由是一个组积压不许拖住另一个（撤稿被发布队列堵住 = 已下线内容还在被引用）。
- **`RETRACT` 失败永不进 retry**：置 `COMPENSATION_REQUIRED`，补偿入口是重新发起
  `POST /wiki-pages/{pageId}/retract`（生成新任务行，旧行留证）。重放撤稿会把正在下线的页面
  重新索引出第二份有效索引，与 P1 删除 Saga 的规则同构。
- `PUBLISH` 只有暂时性错误进退避；永久性错误（含 `DOCUMENT_SIGNATURE_MISMATCH` 式的 `bodyDigest`
  不符、`WIKI_SOURCE_UNAVAILABLE`）直接 `FAILED` 并写死信判定。
- 幂等：`eventId = UUID.nameUUIDFromBytes(taskId + ":" + attempts)`，与 P1 `RetryPump` 同一写法，
  重复通知由 `event_consumption` 主键吸收。
- 消费者拿到事件后**不信任事件内容做授权**：回查 MySQL 取正文与 `status/published_revision_id/ACL`
  （引擎过滤只缩小候选，不是授权判定，ADR-002）。
