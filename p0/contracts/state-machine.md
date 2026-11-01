# P0 文档/任务状态机（冻结）

## 文档状态 `document.status`

```
UPLOADED ──enqueue──▶ QUEUED ──claim──▶ PARSING ──▶ INDEXING ──▶ READY
   │                    │                  │            │          │
   │                    └──── retry ───────┴────────────┘          │
   │                          (暂时性失败，回到 QUEUED/可重放)        │
   └── permanent error ──▶ FAILED ◀────────────────────────────────┘
READY/FAILED/UPLOADED ──delete──▶ DELETING ──engine gone & object retained/purged──▶ DELETED
DELETING ──任一步失败──▶ COMPENSATION_REQUIRED ──重新 DELETE──▶ DELETING
```

| 迁移 | 前置校验 | 失败分类 |
|---|---|---|
| UPLOADED→QUEUED | outbox 事件已发布；object key 存在且 SHA-256 已记录 | — |
| QUEUED→PARSING | 任务被 `operationId` 幂等认领，租约 `lease_until` 未过期 | — |
| PARSING→INDEXING | 引擎解析返回非空结构；格式受支持；原件 SHA-256 与登记值一致 | 格式不支持/摘要不符 → FAILED（永久） |
| INDEXING→READY | 引擎返回 engine_doc_id 且索引可见；本地持久化成功 | 引擎超时/不可用/索引失败 → 重试主题；超过上限 → DEAD_LETTERED + DLQ |
| READY→DELETING | 检索先冻结（引擎下线或 ACL 屏蔽） | 任一步失败 → COMPENSATION_REQUIRED，绝不回 READY |
| DELETING→DELETED | 已确认可检索性消失；原件按 `purge` 策略保留或删除 | 原件删除失败不影响 DELETED，但登记孤儿对象对账 |

规则：
1. 每次迁移必须校验前置状态（`UPDATE ... WHERE status = 前置态`），非法迁移返回
   `INVALID_STATE_TRANSITION`（HTTP 409）并写审计。
2. 重复上传、事件重复投递、消费者重启、引擎超时都不得产生第二个有效索引：
   认领条件 = `ingest_task.status` 可认领 AND `lease_until` 过期或未设置 AND 幂等键未处理过。
3. 租约到期未完成的任务由后续投递重新认领，`attempts+1`。
4. 永久错误不进入重试主题，与 `ErrorCode.permanent()` 一一对应：
   `DOCUMENT_FORMAT_UNSUPPORTED`、`DOCUMENT_SIGNATURE_MISMATCH`、`VALIDATION_FAILED`、
   `CROSS_TENANT_ACCESS`、`ACCESS_DENIED`。
   暂时性错误（`transientFailure()`：`ENGINE_TIMEOUT`/`ENGINE_UNAVAILABLE`/`ENGINE_INDEX_FAILED`/
   `STORAGE_UNAVAILABLE`/`MODEL_UNAVAILABLE`/`CONCURRENCY_LIMITED`）
   才进入 `kb.ingest.retry.v1`，退避 `backoff_base_ms * 2^attempts`，上限 `max_attempts`。
   **【实现未达成】** 本条的退避在 P0 代码里没有被执行：`claimTask` 只按状态认领、不比较 `next_run_at`，
   重试消息一到就立刻执行（实测 5 次尝试 12.70s 跑完，契约下界 60s；`evaluation/reports/fault-drill.md` FD-09）。
   这里是**目标契约**而非现状描述，P1 backlog #18 按本条验收；消费方不得依赖"退避已生效"来安排停机窗口。
   Redis 不可用不是失败：授权缓存只是优化，任何缓存异常都退回 MySQL 判定（fail-close），
   因此没有 `CACHE_UNAVAILABLE` 这个错误码，任务也不会因 Redis 而重试或进 DLQ。
5. 删除 Saga 的失败**不进入** `kb.ingest.retry.v1`：那条主题由入库消费者消费，重放会把正在
   下线的文档重新索引出第二份有效索引。删除失败一律置 `COMPENSATION_REQUIRED`（任务置 FAILED），
   补偿入口是重新发起 `DELETE /documents/{id}`（该状态允许再次删除），检索冻结由 `DELETING`/
   `COMPENSATION_REQUIRED` 状态在授权层挡住，不依赖引擎是否已删干净。
6. `FAILED` 文档可由用户 `POST /documents/{id}/retry` 重新入队，永久性错误直接拒绝
   （`TASK_NOT_RETRYABLE`）；`DEAD_LETTERED` 任务只能经 DLQ 审计入口受控重放。

## 任务状态 `ingest_task.status`

`PENDING → CLAIMED → RUNNING → SUCCEEDED`
分支：`RUNNING → RETRY_SCHEDULED →（重新投递）CLAIMED`；
永久错误或删除 Saga 失败 → `RUNNING → FAILED`；
暂时性错误且 `attempts` 已达 `max_attempts` → `RUNNING → DEAD_LETTERED`（二选一，不会同时置两个终态），
同时经 outbox 向 `kb.ingest.dlq.v1` 写 `INGEST_DEAD_LETTERED` 信封，文档侧仍落 `FAILED`。

## 与 Kafka offset 的关系

Kafka offset 不作为业务状态。业务状态只存在 MySQL；消费者在引擎确认成功并落库之后才提交 offset，
因此崩溃后重复投递由幂等认领挡住，而不是依赖 exactly-once。
