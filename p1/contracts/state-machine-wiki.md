# Wiki 状态机契约（冻结件）

适用范围：pilot 侧（`com.acme.kb.p1.wiki`）。本文件与 Java 边表
`com.acme.kb.p1.wiki.WikiStateMachine.EDGES` **逐条机器比对**，缺边或多边即 ERROR：

```bash
python p1/scripts/24-check-contracts-p1.py
```

比对规则（只比这四列，其余列为人读说明）：

- 只解析「迁移边表」中第一形如 `E\d+ / R\d+ / S\d+` 的行；
- 取 `(id, axis, from, to)` 四元组与 Java `edge("E1", "PAGE", "DRAFT", "IN_REVIEW", ...)` 的前四参比对；
- 两个方向都必须严格相等：文档有而 Java 无 → `边缺失`；Java 有而文档无 → `边未登记`。

状态名必须同时是 Java 枚举常量（`WikiPageStatus` / `WikiRevisionStatus` / `WikiSyncStatus`），
枚举里没有的状态名出现即 ERROR。

---

## 1. 三轴正交（D-02）

| 轴 | 载体 | 枚举 | 谁写 | 是否本状态机管辖 |
|---|---|---|---|---|
| 审核态 | `wiki_page.status` | `DRAFT / IN_REVIEW / PUBLISHED / RETRACTING / RETRACTED / ARCHIVED` | 用户与审核人的 HTTP 动作 | 是 |
| 修订态 | `wiki_revision.status` | `DRAFT / PENDING / APPROVED / REJECTED / SUPERSEDED` | 审核动作 | 是 |
| 同步态 | `wiki_sync_task.status` | `PENDING / CLAIMED / RUNNING / RETRY_SCHEDULED / SUCCEEDED / FAILED / COMPENSATION_REQUIRED / DEAD_LETTERED` | worker | 是（边表 W1 冻结，消费者 W2 接线） |
| 新鲜度 | `wiki_page.freshness` | `FRESH / STALE / EXPIRED` | 调度器（派生位） | **否——不建边** |

新鲜度不建边的理由（照 §3 D-02 的裁定）：**过期但已发布的页面是"需要治理"，不是"状态迁移"**。
把 `EXPIRED` 塞进状态机会让 `IN_REVIEW → PUBLISHED` 的边表出现语义分叉（同一边要按有效期走两套校验）。
`wiki_source` 断链同样只写 `freshness = STALE` + 计数，不阻断发布（D-10）。

`wiki_page.published_revision_id` 是**指针**：检索回查 MySQL 时以它为准，指针不匹配的引擎命中一律丢弃并计数。
指针移动只发生在 `E3`/`E4`，且与 `audit_event(action = wiki.approve)` 同事务。

## 2. 迁移边表

「失败码」列指该迁移**前置校验不通过**时返回的码（见 `p1/contracts/error-codes.md`）；
`UPDATE ... WHERE status = 前置态` 影响 0 行一律 `INVALID_STATE_TRANSITION`(409) **并写审计**，
不静默重试。

| id | axis | from | to | 触发 | 前置校验 | 失败码 | 审计动作 | 接线 |
|---|---|---|---|---|---|---|---|---|
| `E1` | PAGE | `DRAFT` | `IN_REVIEW` | `submit` | `If-Match` 等于 `current_revision_no`；actor 具 `wiki:submit`；该修订至少 1 条 `wiki_source`；`valid_until` 非过去 | `ACCESS_DENIED` / `WIKI_SOURCE_UNAVAILABLE` / `WIKI_REVISION_LOST` | `wiki.submit` | W1 |
| `E2` | PAGE | `IN_REVIEW` | `DRAFT` | `reject` | actor 具 `wiki:review`；`decision_note` 非空 | `WIKI_REVIEWER_NOT_ASSIGNED` / `VALIDATION_FAILED` | `wiki.reject` | W1 |
| `E3` | PAGE | `IN_REVIEW` | `PUBLISHED` | `approve` | actor 具 `wiki:review`；`revision.author != actor`；来源全部可提交 | `WIKI_REVIEW_SELF_APPROVAL` / `WIKI_REVIEWER_NOT_ASSIGNED` | `wiki.approve` | W1 |
| `E4` | PAGE | `PUBLISHED` | `PUBLISHED` | `approve` | 同 `E3`；生效对象是 `revision_no = N+1`，旧 N 指针被替换 | 同 `E3` | `wiki.approve` | W1 |
| `E5` | PAGE | `PUBLISHED` | `RETRACTING` | `retract` | actor 具 `wiki:retract`；同事务写 `wiki_sync_task(operation=RETRACT, status=PENDING)` | `INVALID_STATE_TRANSITION` | `wiki.retract` | W1（任务不发布，见 §6） |
| `E6` | PAGE | `RETRACTING` | `RETRACTED` | `retract-confirmed` | 同步任务 `SUCCEEDED` 且引擎可见性回查确认索引消失 | — | `wiki.retract` | W2 |
| `E7` | PAGE | `DRAFT` | `ARCHIVED` | `archive` | actor 具 `wiki:retract` | `INVALID_STATE_TRANSITION` | `wiki.archive` | W1 |
| `E8` | PAGE | `RETRACTED` | `ARCHIVED` | `archive` | actor 具 `wiki:retract` | `INVALID_STATE_TRANSITION` | `wiki.archive` | W1 |
| `R1` | REVISION | `DRAFT` | `PENDING` | `submit` | 与 `E1` 同事务；同一页面同时只允许一条非终态修订 | `INVALID_STATE_TRANSITION` | `wiki.submit` | W1 |
| `R2` | REVISION | `PENDING` | `APPROVED` | `approve` | 与 `E3`/`E4` 同事务 | `WIKI_REVIEW_SELF_APPROVAL` | `wiki.approve` | W1 |
| `R3` | REVISION | `PENDING` | `REJECTED` | `reject` | 与 `E2` 同事务；`decision_note_digest` 必落 | `VALIDATION_FAILED` | `wiki.reject` | W1 |
| `R4` | REVISION | `APPROVED` | `SUPERSEDED` | `approve` | N+1 通过时，把旧 N 置 `SUPERSEDED`；**不删旧行**（修订历史与差异计算依赖它） | — | `wiki.approve` | W1 |
| `S1` | SYNC | `PENDING` | `CLAIMED` | `claim` | `next_run_at IS NULL OR next_run_at <= now`，且无未过期租约；`attempts+1` | — | — | W2 |
| `S2` | SYNC | `CLAIMED` | `RUNNING` | `start` | 租约持有者本人 | — | — | W2 |
| `S3` | SYNC | `CLAIMED` | `RETRY_SCHEDULED` | `lease-expired` | 租约已过期（`lease_until < now`），由重新认领路径复位 | — | — | W2 |
| `S4` | SYNC | `RUNNING` | `SUCCEEDED` | `succeeded` | 引擎确认新索引可见后才可置成功 | — | `wiki.publish.sync` | W2 |
| `S5` | SYNC | `RUNNING` | `RETRY_SCHEDULED` | `transient-failure` | 仅暂时性错误（§4）；写 `next_run_at = now + backoff_base × 2^attempts` | — | `wiki.publish.sync` | W2 |
| `S6` | SYNC | `RUNNING` | `FAILED` | `permanent-failure` | 永久性错误（§4）立即失败，**不进退避、不发 retry 事件** | — | `wiki.publish.sync` | W2 |
| `S7` | SYNC | `RUNNING` | `DEAD_LETTERED` | `attempts-exhausted` | `attempts >= max_attempts`；同事务写 `kb.wiki.dlq.v1` 信封 | — | `wiki.publish.sync` | W2 |
| `S8` | SYNC | `RUNNING` | `COMPENSATION_REQUIRED` | `retract-failure` | 撤稿链路任一步失败；`RETRACT` 类任务**绝不进任何 retry 主题** | — | `wiki.retract` | W2 |
| `S9` | SYNC | `RETRY_SCHEDULED` | `CLAIMED` | `claim` | 到期后重新认领（靠 `next_run_at`，不靠 sleep） | — | — | W2 |

租约丢失不是一条独立的边：`CLAIMED` 丢租约走 `S3`、`RUNNING` 丢租约走 `S5`（复位为 `RETRY_SCHEDULED`
并等下一次投递重新认领），与 P1 `reclaimExpiredTask` 一致。心跳没续上的 worker 之后调用
`finishTask` 会因 `lease_owner` 条件影响 0 行，只能记 `SKIPPED_NOT_CLAIMABLE` 日志，
**不许**把已接手的任务改写成终态。

`RETRACTING → RETRACTED`（`E6`）之外没有任何边把页面送回 `PUBLISHED`：撤稿一旦发起，
失败只停在 `RETRACTING` + 任务 `COMPENSATION_REQUIRED`，补偿入口是**重新发起** `POST /wiki-pages/{id}/retract`
（生成新任务行，旧行留作取证）。与 P1 删除 Saga「失败绝不回 `READY`」同构。

`RETRACTED → PUBLISHED` 无边：想让已撤稿内容重新可见，必须开新修订走 `E1/E3`，
否则会绕过审核。

## 3. 禁止迁移（必须有用例，见 §5）

| 请求 | 结果 | 为什么 |
|---|---|---|
| `PUBLISHED → ARCHIVED` | 409 `INVALID_STATE_TRANSITION` | MySQL 归档而引擎索引仍在 ⇒ 「撤稿未生效」是 §14 列出的治理事故。必须先 `E5` 进 `RETRACTING`，由 `E6` 确认索引消失后才 `E8`。**这是对 §5.1 里「任意非 IN_REVIEW → ARCHIVED」那一条的收紧**，裁定与复测项见 `p0/decisions/ADR-012-…` 与本文件 §7 |
| `RETRACTING → ARCHIVED` | 409 | 同上：索引尚未确认消失 |
| `IN_REVIEW → ARCHIVED` | 409 | 会丢失审核证据链；须先 `E2` 退回 |
| `RETRACTED/ARCHIVED → IN_REVIEW` | 409 | 已下线内容不能直接进入审核 |
| 对 `DRAFT` 页面 `approve` | 409 + 审计 `DENY` | 审核只作用于 `PENDING` 修订 |
| `APPROVED → DRAFT` | 409 | 已生效修订不可原地改正文，改动等于新修订（`R4` + 新行） |
| `SUPERSEDED / REJECTED / FAILED / SUCCEEDED / DEAD_LETTERED / COMPENSATION_REQUIRED` 的任何出边 | 409 | 终态；重放走新行（版本化审计要求） |

## 4. 失败分类与路由（AGENTS.md §8）

- **永久性**（不进 retry、不退避、直接 `S6`）：`VALIDATION_FAILED`、`ACCESS_DENIED`、
  `CROSS_TENANT_ACCESS`、`WIKI_SOURCE_UNAVAILABLE`、`WIKI_REVIEW_SELF_APPROVAL`、
  `WIKI_REVIEWER_NOT_ASSIGNED`、`WIKI_TITLE_INVALID`、`DOCUMENT_FORMAT_UNSUPPORTED`、
  `DOCUMENT_SIGNATURE_MISMATCH`。
- **暂时性**（`S5` 退避，`backoff-base-ms × 2^attempts`，封顶 `backoff-max-ms`）：`ENGINE_TIMEOUT`、
  `ENGINE_UNAVAILABLE`、`ENGINE_INDEX_FAILED`、`STORAGE_UNAVAILABLE`、`MODEL_UNAVAILABLE`、
  `CONCURRENCY_LIMITED`。
- 超过 `max_attempts` → `S7` + `kb.wiki.dlq.v1` 信封；页面停在原状态，治理视图可见，
  受控重放走新任务行（沿用 P1 DLQ「不自动重放」的判据）。
- `RETRACT` 类任务的失败**永不**产生 retry 事件（D-04）：重放会把正在下线的页面重新索引出第二份有效索引。
- 分类的权威实现是 `ErrorCode.permanent()` / `ErrorCode.transientFailure()`，
  本清单与代码不一致时**以代码为准并回来改本表**（三方同步由 24 号脚本核对错误码侧）。

## 5. 负向用例（AGENTS.md §5：新状态必须补迁移表**与**负向用例）

「W1」= 本轮已可证明（不发引擎请求、不依赖真实 IdP）；「F-1 待复」= 需真引擎联调（§1.3.1 阻断级地基）。

| # | 用例 | 期望 | 实现位置 | 状态 |
|---|---|---|---|---|
| 1 | 对 `DRAFT` 页面执行 approve | 409 `INVALID_STATE_TRANSITION`，审计有 `DENY` 记录 | `WikiReviewTest` | W1 |
| 2 | 作者自批 | 403 `WIKI_REVIEW_SELF_APPROVAL`，除审计外无副作用 | `WikiReviewTest` | W1 |
| 3 | `If-Match` 指向旧 `revision_no` 保存 | 409 `WIKI_REVISION_LOST`，响应含当前 `revision_no` 与最新 `body_digest` 前 12 位（**不返回他人正文**），页面内容不变 | `WikiConcurrencyTest` | W1 |
| 4 | 两个并发 `submit` 同一页 | 恰一个成功，另一个 409，`wiki_revision` 无第三行 | `WikiConcurrencyTest` | W1 |
| 5 | approve 与 retract 并发 | 终态唯一，不出现"既发布又撤稿"的两份有效可见性 | `WikiConcurrencyTest` | W1（状态轴）/ F-1 待复（索引侧） |
| 6 | 重复 approve 同一 revision | 幂等：不产生新修订、不重复写 `wiki_sync_task` | `WikiReviewTest` | W1 |
| 7 | 撤稿后检索同一 `pageId` | 时限内 0 命中；`citation` 预览 404/409 | `22-verify-wiki-acl.py` | F-1 待复 |
| 8 | 草稿正文关键词跑全库检索 | 0 命中；草稿不可达（空 scope 不发请求） | `22-verify-wiki-acl.py` | F-1 待复 |
| 9 | 跨租户命中 `pageId` / `revisionId` / `caseId` | 404，`CROSS_TENANT_ACCESS` 只写审计不作响应 | `WikiReviewTest` | W1 |
| 10 | 撤稿期间引擎不可用 | 停 `RETRACTING` + 任务 `COMPENSATION_REQUIRED`，不回 `PUBLISHED`、不进 retry 主题 | `WikiStateMachineTest`（边表）+ `13-fault-drill.py` | 边表 W1，引擎侧 F-1 待复 |
| 11 | worker 在 sync `RUNNING` 时被 kill | 租约到期后重新认领，`attempts+1`，无第二份索引 | `21-verify-wiki-publication.py` | F-1 待复 |
| 12 | 提醒规则同一天扫描两次 | 站内通知恰一条（`(page_id, rule, date_bucket)` 唯一键 + `event_consumption`） | `WikiReminderConstraintTest`（数据层唯一键） | 数据层 W1，调度器 W3 |

用例 5/10/11 的「索引侧」部分在 stub 上证明等于没证明（§1.3.1 F-1 判据），
因此本轮**不声称**已通过，只落边表与状态轴断言。

## 6. 接线状态（本契约的现状边界，不是目标）

| 内容 | W1（本轮） | 说明 |
|---|---|---|
| 三轴状态枚举 + 21 条边 + §3 禁止迁移 | **已实现并测试** | `WikiStateMachine`，纯数据库层，零引擎调用 |
| `PAGE`/`REVISION` 轴的全部动作 | **已实现** | submit/approve/reject/archive/retract（`E6` 除外） |
| `SYNC` 轴（`S1…S9`） | **边表已冻结，消费者未接线** | `wiki_sync_task` 行会由 `E5`/`E3` 事务写入并保持 `PENDING`；`WikiSyncConsumer`/`WikiRetractConsumer` 需 F-1 真引擎，属 P2-W2 |
| `E6` `RETRACTING → RETRACTED` | **未实现** | 依赖引擎可见性回查；实现前撤稿停在 `RETRACTING`，检索层按规则 2 一律拒绝，不产生越权可见性 |
| Outbox → `kb.wiki.sync/retract/dlq.v1` 发布 | **未接线** | schema 已在 `p0/contracts/kafka/` 冻结并登记；生产方在 W2 落地，登记见 `p0/decisions/P1-backlog.md` |
| `WIKI_SYNC_COMPENSATION_REQUIRED` 错误码 | **未声明** | 门户里没有 `WikiRetractConsumer`，没有任何路径能发出它；按「声明必须可产生」不进枚举、码表与 OpenAPI（`p1/contracts/error-codes.md` 文末同条） |

## 7. 与 P1 状态机的关系

- 复用 `document.status` 的「`DELETING` 挡检索」同构做法：`RETRACTING` 的页面在检索与引用预览层**一律拒绝**，
  不依赖引擎是否已删干净（规则：状态轴的拒绝优先于派生索引）。
- 复用 `ingest_task` 的认领语义：`S1`/`S9` 的 `WHERE` 条件照抄 `PipelineCommandMapper.claimTask`
  （状态 + 租约 + `next_run_at` 三条件同时满足），退避由数据库调度保证，不依赖消费者 sleep。
- 复用 `event_consumption` 账本挡重复投递；`wiki_sync_task` 的确定性 `eventId` 用
  `UUID.nameUUIDFromBytes(taskId + ":" + attempts)`（与 P1 `RetryPump` 同一写法）。
- 不动 `WorkspaceRole` 枚举（D-05）：治理权限由 `WikiCapabilities` + `wiki_review_assignment` 计算，
  因此 P1 的授权矩阵证据（`05-verify-authz.py`）继续有效。

## 8. 冻结与变更

本文件冻结后，改边表需**同时**改 `WikiStateMachine.EDGES`、§5 用例与 `openapi-1.2.yaml` 对应操作，
否则 24 号脚本非零退出。**只允许追加边，不允许删边或改变已有边的语义**（删边=状态机收紧，需版本化评审）。

§3 的「`PUBLISHED → ARCHIVED` 禁止」是对设计文档 §5.1 通用行「任意非 `IN_REVIEW` → `ARCHIVED`」的显式收紧，
理由是 §14「撤稿未生效」风险与红线「不泄露」；F-1 关闭后若要在真引擎上放开该边，必须新增一条
`E9 PAGE PUBLISHED → ARCHIVED` 边 + 对应负向用例改写 + 新 ADR，不得默默放开。

## 9. 审计动作清单（`audit_event.action`）

**命名裁定**：设计文档 §6.1 用 `WIKI_APPROVE` 这类大写下划线名，但 pilot 的审计语料与
`p0/contracts/kafka/audit-event.v1.schema.json` 一律是**点小写**
（`document.upload`、`chat.ask`、`kb.read`、`citation.preview`、`document.delete.requested`…）。
按冲突裁定顺序「冻结契约文件 > 实际代码 > 文档散文」，P2 的 Wiki 动作**全部取点小写**，
文档散文里的大写名不再采用。schema 侧由 `^[a-z][a-z0-9.]{2,63}$` 强制，写成大写会被 24 号脚本判 ERROR。

| action | 触发 | 资源 | 结果 | 接线 |
|---|---|---|---|---|
| `wiki.page.create` | `POST /wiki-pages` | `wiki_page` | ALLOW | W1 |
| `wiki.revision.read` | 读修订（无 `wiki:review` 读非当前修订时记 DENY） | `wiki_revision` | ALLOW / **DENY** | W1 |
| `wiki.revision.save` | `PUT /wiki-pages/{id}/draft` | `wiki_revision` | ALLOW | W1 |
| `wiki.source.attach` | `POST /wiki-pages/{id}/sources` | `wiki_source` | ALLOW | W1 |
| `wiki.source.detach` | `DELETE /wiki-pages/{id}/sources/{sourceId}` | `wiki_source` | ALLOW | W1 |
| `wiki.submit` | `E1`/`R1` | `wiki_page` | ALLOW / DENY | W1 |
| `wiki.approve` | `E3`/`E4`/`R2`/`R4` | `wiki_page` | ALLOW / DENY | W1 |
| `wiki.reject` | `E2`/`R3` | `wiki_page` | ALLOW | W1 |
| `wiki.publish.sync` | `S4`/`S5`/`S6`/`S7` | `wiki_sync_task` | ALLOW | W2（消费者未接线，故 W1 不产生） |
| `wiki.retract` | `E5`/`E6`/`S8` | `wiki_page` | ALLOW | `E5` W1，`E6`/`S8` W2 |
| `wiki.archive` | `E7`/`E8` | `wiki_page` | ALLOW | W1 |
| `wiki.owner.change` | `PATCH /wiki-pages/{id}/governance` | `wiki_page` | ALLOW | W1 |
| `wiki.validity.update` | `PATCH /wiki-pages/{id}/governance` | `wiki_page` | ALLOW | W1 |
| `wiki.review.assign` | 审核指派变更（`wiki_review_assignment` 写入） | `wiki_review_assignment` | ALLOW | W3：W1 只读该表算能力，指派由 `p1/compose/README.md` 的引导 SQL 落，没有端点 ⇒ 本轮不可产生 |
| `correction.case.assign` | 纠错单受理/改派 | `correction_case` | ALLOW | W3 |
| `correction.case.close` | 纠错单关闭（含 `invalid`） | `correction_case` | ALLOW | W3 |
| `notification.read` | 站内通知标记已读 | `notification` | ALLOW | W3 |
| `reminder.fired` | 提醒规则命中并生成通知 | `wiki_reminder` | ALLOW | W3（调度器） |

DENY 与 ALLOW 都走 `AuditService`：`DENY` 只在**同一请求事务尚未写库**时可用 `recordInTx`，
一旦需要「拒绝也要留痕但业务不回滚」，用 `recordIndependent`（REQUIRES_NEW），与 P1
`citation.preview` 的 DENY 写法一致。**正文、`decision_note`、评论原文一律不进审计载荷**，
只写 `body_digest` / `decision_note_digest` 与计数（红线 1）。
`reminder.fired` 的载荷只允许 `pageId`、`rule`、`date_bucket`、`channel`，不含通知正文。

