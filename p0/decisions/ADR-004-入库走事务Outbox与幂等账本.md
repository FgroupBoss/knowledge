# ADR-004 入库用事务 Outbox + 消费幂等账本，宁可重复投递也不丢事件

- 状态：已采纳（2026-10-31）
- 背景：MySQL 与 Kafka 之间没有分布式事务。业务写成功但事件没发出去 = 文档永远不进索引；
  事件发成功但业务回滚 = 幽灵索引。同时 at-least-once 语义必然带来重复投递。
- 决定：
  1. 业务事务里只写 `outbox_event`（与 `document`、`ingest_task` 同一提交），
     由 `OutboxPublisher` 定时（500ms）投递成功后打 `published_at`；崩溃最多导致重投，不会丢。
  2. 消费端用 `event_consumption(event_id PRIMARY KEY, outcome, seen_count)` 做幂等账本：
     同一 `eventId` 已 `SUCCEEDED` 再收到就跳过，只累加 `seen_count`；
     认领任务再看 `ingest_task` 的 `operation_id` + 租约，两层去重。
  3. 失败按 `ErrorCode.transientFailure()` 二分：暂时性（引擎超时/不可用、存储不可用、模型不可用、
     并发限流）退避 `backoff_base_ms * 2^attempts` 进 `kb.ingest.retry.v1`，
     超过 `max-attempts=5` 写 `INGEST_DEAD_LETTERED` 信封到 `kb.ingest.dlq.v1` 并把文档落 `FAILED`；
     永久性（格式不支持、签名不符、校验失败、跨租户）直接 `FAILED`，不占重试额度。
     **退避目前是坏的**（见"后果"），设计意图不变，实现必须补上到期判定。
  4. 租约 `lease_seconds=45`，认领条件是 `PENDING|RETRY_SCHEDULED`，或 `CLAIMED|RUNNING` 且租约已过期，
     或持有者就是自己——worker 崩溃后由下一次投递接管，不需要人工复位。
     同一条 SQL 也就是退避失效的现场：它没有 `next_run_at <= now` 的到期判定。
  5. 上传是"先写对象、再开业务事务"：业务事务失败会留下**孤儿对象**（日志记 `orphan_object_pending`），
     P0 只做按内容哈希对账取证，自动清理放 P1。
  6. 死信不被自动复活：`kb.ingest.dlq.v1` 的信封只写不消费，重放入口是人工审计通道；
     用户侧 `POST /documents/{id}/retry` 只对 **FAILED + 暂时性错误码** 开放，它开一个**新 operation**
     并写 `document.retry` 审计，绝不把原 `DEAD_LETTERED` 任务改回可运行态（永久性错误码返回 `TASK_NOT_RETRYABLE`）。
- 后果：
  - 正面：`无重复有效索引` 与 `积压可读` 都有直接证据；broker/存储故障表现为延迟而不是数据丢失。
  - 负面：事件至少一次 + 定时轮询意味着收敛延迟下限约等于发布周期；孤儿对象目前只报警不清理。
  - **缺陷（2026-11-01 故障注入演练 FD-09 实测）**：指数退避没有被任何一处执行。
    存储不可用触发的重试，首跳只等了 **104ms** 就被消费（契约要求 ≥ `2000×2 = 4000ms`），
    5 次尝试在 **12.70 秒**内跑完并落死信（同一任务 `created_at → updated_at` 实测），
    而按契约下界（各跳之和）应不少于 **60 秒**。根因：`PipelineService.fail()` 把 `notBefore` 写进重试事件、
    把 `next_run_at` 写进 `ingest_task`，但 `PipelineCommandMapper.claimTask` 的认领条件只有
    `status IN ('PENDING','RETRY_SCHEDULED')`，从不比较 `next_run_at`；消费者拿到重试消息立刻认领。
    影响的是**韧性而不是正确性**：终态仍可恢复（FD-11 用户 retry 收敛 READY、FD-12 重新上传成功、
    索引始终一份），但依赖抖动几十秒就会把那期间所有在途文档全部打成死信，
    把"短故障自动恢复"变成"人工逐份重推"。这与 P0 硬门槛"引擎短故障后最终状态可恢复"的意图只差在半自动，
    因此按例外项签署而不是判 No-Go，修法与验收见 P1 backlog #18。
- 证据：`evaluation/reports/ingest-run.md`（19/19，含 TX/ID/BK/ST/LIM 家族）、
  `evaluation/reports/fault-drill.md` FD-05~FD-08（Kafka 停机积压、同 eventId 重投被挡、
  过期租约被接管）、FD-10~FD-12（STORAGE_UNAVAILABLE 退避到上限→DEAD_LETTERED + DLQ 信封 +
  文档 FAILED；用户 retry 开新任务且不复活死信；存储恢复后重新上传成功）、
  FD-09（**不通过**，退避未生效的实测时间线）。
- 责任人 / 复测：Java；接入真引擎后必须重跑 FD-07/FD-08（2026-11-13 前），
  退避修复的复测目标是 **2026-11-06**，并把"孤儿对象自动对账清理"作为 P1 条目验收。
