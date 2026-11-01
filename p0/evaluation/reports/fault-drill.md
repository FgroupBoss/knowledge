# 故障注入演练

- 时间：2026-11-01 02:33:07
- 注入手段：`compose stop redis|kafka|minio`、`FLUSHALL`、`kafka-console-producer` 原样重投、root 改 `ingest_task` 租约时间戳
- 数据边界：只注入故障不删业务数据；库内写入仅租约列；口令与对象 key 一律不外传
- 结论：**15/16 条通过**

| 用例 | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| FD-01 | Redis 被清空后问答仍可用，且缓存按键重新建立（fail-close，不降级为放行） | PASS | FLUSHALL rc=0 OK |
| FD-02 | 缓存清空后仍从权威库出结论：两次问答都 200、引用集合一致、且没有产生任何 Redis 错误 | PASS | misses 225→227, hits 0→0, 引用文档=['c3cb70cf-4acb-433e-8319-9428930bf8d3', 'c3cb70cf-4acb-433e-8319-9428930bf8d3'] |
| FD-03 | Redis 整机停机期间问答仍返回 200（缓存不是授权裁决者，权限判定落回 MySQL） | PASS | status=ANSWERED redisErrors=12→16 |
| FD-04 | Redis 停机被记为可观测错误计数，而不是静默吞掉 | PASS | redisErrors 12 → 16 |
| FD-05 | Kafka 停机期间上传仍返回 202 并落入待发布 outbox（业务事务不依赖 broker 在线） | PASS | 202 outboxUnpublished=1 oldestAge=0s |
| FD-06 | Broker 恢复后积压被排空并收敛为 READY（无事件丢失、无悬挂任务、消费组 lag 归零） | PASS | status=READY outboxUnpublished=0 lag=0 |
| FD-07 | 同一 eventId 重投被幂等账本挡下：seen_count 上升但索引块数一字不变，文档仍 READY | PASS | seen/outcome=3/SUCCEEDED chunks 1→1 |
| FD-08 | 持有过期租约的 RUNNING 任务可被新投递接管，重新索引后回到 READY 且不翻倍索引 | PASS | outcome=SUCCEEDED lease_owner=NULL status=READY chunks 1→1 |
| FD-09 | 暂时性故障按契约退避重试：第 1→2 次尝试的等待不得少于 4.0s（`backoff-base-ms=2000` × 2^attempts） | FAIL | 首跳实际等待 104.0ms（契约要求 ≥4000.0ms，事件里写的 notBefore=2026-10-31T18:32:41.587202200Z）；5 次尝试共用 12.70s 就跑完，而整条退避链的契约下界是 60s |
| FD-10 | 重试到上限后落为 DEAD_LETTERED + 文档 FAILED + 死信计数 +1（不静默丢弃） | PASS | task=DEAD_LETTERED doc=FAILED code=STORAGE_UNAVAILABLE dlq 2→3 |
| FD-11 | 用户 retry 开的是新任务而不是复活死信：202 + `document.retry` 审计 + 新 operation + 原任务仍 DEAD_LETTERED + DLQ 事件未增加；存储恢复后收敛 READY 且只有一份有效索引 | PASS | 202/ 新operation=3f24b07b 原任务=DEAD_LETTERED dlq事件=1 chunks=1 status=READY |
| FD-12 | 存储恢复后同一内容重新上传即可正常入库（版本递增，死信不复活、不自动重放） | PASS | status=READY version=2 dlq=3 |
| FD-13 | 删除把检索与读取入口一起收干净（文档行 DELETED、对外 404、0 块、0 份可检索引擎文档、全库 0 幽灵索引），但非 purge 语义下原件不删（桶内对象数不变） | PASS | DELETE=202 doc=DELETED GET=404/RESOURCE_NOT_FOUND chunks=0 可检索=0 ghost=0 桶内对象 294→294 |
| FD-14 | 同一原件重新上传后索引块数与删除前一致——索引确为可从原件重建的派生物 | PASS | version=3 chunks 1→1 |
| FD-15 | 演练全程后：跨租户引用 0 例，且没有超过 10 分钟仍卡在中间态的文档 | PASS | crossTenantCitations=0 stuck=0 |
| FD-16 | 演练结束依赖全部回到 healthy（不给下一轮留半个环境） | PASS | redis/kafka/minio/mysql healthy=True |

## 取证与说明

- 演练发现（缺陷，不作为通过项）：FLUSHALL 之后 hits 始终为 0。水位键 `:aclwm:<kbId>` 只在权限变更时写入，读路径 `find()` 一见水位缺失就回源 MySQL 且从不补写水位——Redis 决策缓存目前只写不读。正确性不受影响（fail-close 回源，引用集合与清空前一致），但加速收益为 0，每轮问答还多付一次水位读 + 一次决策读。已写入 ADR-002 后果段与 P1 backlog（修法：回源成功后由读路径建立水位，或改为不依赖水位的版本号内联校验）。
- 演练发现（缺陷，FD-09 因此不通过）：暂时性故障的指数退避没有生效——首跳只等了 104.0ms 就被消费（要求 ≥4000ms），5 次尝试在 12.70s 内跑完并落死信（退避链下界 60s）。根因在 `PipelineCommandMapper.claimTask`：它按 `status IN ('PENDING','RETRY_SCHEDULED')` 认领任务，完全不看 `next_run_at`；`PipelineService.fail()` 只是把 `notBefore` 写进重试事件和任务行，消费端拿到消息立刻认领，退避形同虚设。影响面不是正确性（终态仍可恢复，见 FD-11/FD-12）而是韧性：依赖抖动几十秒就会把这期间所有在途文档打成死信，把"短故障自动恢复"变成"人工逐份重推"。已登记 ADR-004 后果段与 P1 backlog #18（修法：claimTask 增加 `next_run_at <= now` 条件，并让重试消费对未到期消息延后投递而不是立即执行）。

## 结论

- 死信、重复投递、租约接管、缓存清空、Broker 积压、删除重建这些**终态正确性**路径全部通过：
  重复投递不产生第二份索引，过期租约被接管，Redis 与 MinIO 停机都不改判权限结论，  原件恢复后索引可逐字重建。
- **未通过项是退避**（FD-09）：契约写的指数退避在生产代码里没有被任何一处执行，暂时性故障会在几秒内被打成死信。这不影响单份文档最终可恢复（FD-11/FD-12 已证），但让"依赖短暂抖动自动恢复"变成"人工逐份重推"，属于韧性缺陷而非正确性缺陷，已登记 ADR-004 与 P1 backlog，并作为 Go/No-Go 的例外签署项。
- 本脚本故意让 FD-09 保持失败状态并返回非 0：演练的目的是暴露失败语义，不是凑齐绿灯。

## 尚未覆盖（如实记录）

- 双实例同时消费时的租约竞争只通过"过期租约被接管"间接验证，未做多门户实例对抢；
- DLQ 受控重放的正向路径需要人工审计入口，P0 只验证了"不许自动重放"这一半；
- 磁盘写满、MySQL 主键冲突风暴、网络分区（一半容器活着）未注入，列入 P1。
