# P2-W0/W1 契约工程与 Wiki 数据模型 · 实跑证据（2026-11-01 实跑）

对应设计：`outputs/P2技术实现文档-本地知识库.md` §11（C-1…C-7）、§12（P2-W0、P2-W1）、§13.1
对应契约：`p1/contracts/openapi-1.2.yaml`、`p1/contracts/error-codes.md`、
`p1/contracts/state-machine-wiki.md`、`p1/contracts/config-catalog-p1.md`、`p0/contracts/kafka/`
对应决议：`p0/decisions/ADR-010-…`～`ADR-014-…`
**本轮范围口径见 §1.6 的裁定：只做 W0 全部 + W1 的迁移/状态机/CRUD/乐观锁/差异；W2/W3/W4 阻断未开工。**

## 口径

- 环境：Windows 11 10.0.26200 x64；Docker server 29.4.0；JDK 21.0.9+7-LTS-338；Maven 3.9.14（一律 `-o` 离线）；
  Python 3.12.10；Git Bash。依赖容器由 `bash p0/scripts/01-stack-up.sh` 起，宿主回环
  mysql 13306 / redis 16379 / kafka 29092 / minio 19000。
- 镜像与摘要（与 `p0/compose/image-manifest.txt` 逐条核对，`docker image inspect` 实测一致）：
  `mysql:8.4@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364`、
  `redis:7.4-alpine@sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2`、
  `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837`、
  `minio/minio:RELEASE.2024-10-13T13-34-11Z@sha256:9535594ad4122b7a78c6632788a989b96d9199b483d3bd71a5ceae73a922cdfa`。
- 数据库：`kb_p1_test`，Flyway 用 `kb_p1_ddl`（DDL），应用用 `kb_p1_app`（仅 SELECT/INSERT/UPDATE/DELETE），
  口令只从 `p1/compose/secrets/` 文件或环境变量进入，本文件不含任何取值。
- 数据集：**无生产语料**。Wiki 用例每个自建一套独立租户/空间/库/3 用户/来源文档，
  来源文档行由测试夹具直插（`sha256` 由行 ID 派生，正文不进任何交付物）。
  因此本报告的计数只证明**行为**，不代表任何内容规模。
- 注入的运行参数（与仓库默认不同项，逐条）：`kb.wiki.enabled=true`、`kb.engine.provider=stub`、
  `kb.engine.embedding-mode=deterministic`、`kb.engine.chat-mode=extractive`、
  `spring.kafka.listener.auto-startup=false`、`kb.ingest.retry-pump-ms=3600000`、
  `kb.ingest.outbox-interval-ms=3600000`、`kb.auth.dev-subject-enabled=true`（**仅 test profile**）。
  后两项把时间驱动的泵停住，避免测试期间 outbox 真投递。

## 执行

```bash
cd p1/portal && mvn -o clean test
python p1/scripts/24-check-contracts-p1.py
python p1/scripts/24-check-contracts-p1.py --selftest
python p0/scripts/04-check-contracts.py
bash p1/scripts/30-sync-migrations.sh
node .qoder/skills/kb-dev-workflow/scripts/check-evidence.cjs
node .qoder/skills/kb-dev-workflow/scripts/check-hygiene.cjs
MSYS_NO_PATHCONV=1 docker exec kb-p0-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

## 结果

| 断言 | 期望（契约条款） | 实测 | 判定 |
| --- | --- | --- | --- |
| PW-01 | V1–V18 只用 DDL 账号跑通、无 pending、逐个带校验；app 账号能 DML 不能 DDL | `FlywayMigrationTest` 5 用例全绿；`Successfully validated 18 migrations`、schema 版本 18；DDL 探测被 `1142` 拒绝 | 通过 |
| PW-02 | Wiki 治理九表与唯一键就位（`uk_wiki_page_kb_slug`/`uk_wiki_rev_page_no`/`uk_wiki_source`/`uk_wiki_task`/`uk_wiki_assign`/`uk_wiki_remind_bucket`/`uk_case_feedback`/`idx_wiki_page_fresh`） | `wikiGovernanceTablesAndKeysExistAfterMigration` 通过：9 表 + 7 关键列 + 8 索引全部存在 | 通过 |
| PW-03 | P1 问答表带 Wiki 接缝且不破坏旧语义（`citation.source_type/page_id/page_revision_no`、`document_id` 可空、`chat_feedback.triaged_case_id/status` 默认 `NEW/DOCUMENT`） | `citationAndFeedbackCarryWikiSeamsWithoutBreakingOldSemantics` 通过；`idx_citation_page` 存在 | 通过 |
| PW-04 | 三轴边表 21 条（E1–E8/R1–R4/S1–S9）与 `state-machine-wiki.md` 逐条对应，缺边即 ERROR | 24 号门禁「状态机边表比对 21」OK；`WikiStateMachineTest` 15 用例含负向边与派生位 `freshness` 无出边 | 通过 |
| PW-05 | §5 负向用例 1/2：DRAFT 页 approve → 409 + `DENY` 审计；作者自批 → `WIKI_REVIEW_SELF_APPROVAL`，除审计外无副作用 | `approvingADraftPageIsRejectedAndAudited`、`authorCannotApproveOwnRevisionEvenWhenAssigned` 通过（后者先补指派行使 capability=true，才能真产出该码） | 通过 |
| PW-06 | §5 用例 3：`If-Match` 指旧修订 → 409 `WIKI_REVISION_LOST`，只给当前/最新修订号与最新 `body_digest` 前 12 位，**不回传他人正文**，页面内容不变 | `staleIfMatchSaveReturnsRevisionLostWithoutLeakingBody` 通过；`metadata_json LIKE '%他人正文%'` 计数 0 | 通过（本轮修复） |
| PW-07 | §5 用例 4：两个并发 `submit` 同一页 → 恰一个成功、另一个 409、`wiki_revision` 无第三行 | `concurrentSubmitsOnTheSamePageYieldExactlyOneWinner` 通过（`CyclicBarrier` + 真 MySQL） | 通过 |
| PW-08 | §5 用例 6：重复 approve 幂等，不产生新修订、不重复写 `wiki_sync_task` | `repeatedApproveIsIdempotent` 通过：2 条 ALLOW（其中 1 条带 `idempotent-replay` 标记）、0 条 DENY、任务行数不增 | 通过 |
| PW-09 | §5 用例 5 状态轴半边：approve 与 retract 并发终态唯一，不出现两份有效可见性 | `approveAndRetractConcurrentlyLeaveASingleEffectiveState` 通过（按不变式断言，索引侧待 F-1 复跑） | 通过（受限） |
| PW-10 | §5 用例 9：跨租户命中 `pageId` 一律 404，`CROSS_TENANT_ACCESS` 只写审计不作响应 | `crossTenantPageHitIsAlwaysFourOhFour` 通过；响应体不含他租户标识符 | 通过 |
| PW-11 | D-05：审核能力只来自 `wiki_review_assignment` 行，不因 `CONTRIBUTOR` 角色放宽 | `reviewCapabilityComesFromTheAssignmentRow` + `WikiCapabilitiesTest` 9 用例通过；非成员先被成员校验挡下 | 通过 |
| PW-12 | D-10 fail-close：无可用来源不得提交；来源断链只标不可用、不阻断已发布读取 | `submitWithoutAnySourceFailsClose`、`brokenSourceMarksUnavailableWithoutBlockingRead` 通过 | 通过 |
| PW-13 | D-07：差异由服务端算，行数/格数超预算降级为「只给计数不给行」，含 200 KiB 量级边界 | `WikiDiffTest` 8 用例通过（1300×1200 行走 `truncated=true` 不进 LCS 矩阵） | 通过 |
| PW-14 | 红线 1：正文与审核意见原文不进审计、契约、报告；`record_hash` 链自洽 | `auditChainStaysValidAndCarriesNoBodyText`（链校验 + 动作名正则 + 正文计数 0）、`rejectKeepsOnlyTheDigestOfTheDecisionNote` 通过 | 通过 |
| PW-15 | backlog #4 语义：审计写不进则业务整体回滚，且不推进链头 | `auditWriteFailureRollsBackTheWholePageCreation` 通过：页面 0 行、`wiki.page.create` ALLOW 0 条、链校验仍成立 | 通过 |
| PW-16 | §1.3.1：W1 不得对引擎发请求，同步只到「任务落库并停在 `PENDING`」 | `publishHappyPathMovesPointerAndLeavesSyncTaskPending` 通过：`wiki_sync_task` 1 行 `PENDING`；`outbox_event` 无 `kb.wiki%` 主题行 | 通过 |
| PW-17 | 功能开关：`kb.wiki.enabled=false` 时 Wiki 路由完全不注册（试点默认关） | `wikiRoutesAreRegisteredOnlyWhenTheFeatureIsEnabled` 通过（`RequestMappingHandlerMapping` 断言） | 通过 |
| PW-18 | §11 C-1…C-7 门禁双向：全绿 + 故意改坏必报错 | 24 号 `0 error / 0 warning`（EXIT=0）；`--selftest` 11/11 捕获（含「审计载荷键改名」证明 `kb.audit.v1` 载荷不是空检查）；04 号 `0 error / 4 warning`（EXIT=0） | 通过 |
| PW-19 | §11 C-6：评审副本与执行源逐文件 SHA-256 一致，副本不多不少 | `30-sync-migrations.sh` 列出 V1–V18 全部 18 个哈希，「本次更新 0 个文件」，EXIT=0 | 通过 |
| PW-20 | §11 C-5：三个新主题在契约、建题脚本与运行态三处一致 | `03-topics.sh` 含 `kb.wiki.sync.v1:3`/`kb.wiki.retract.v1:3`/`kb.wiki.dlq.v1:1`；运行 `kafka-topics --list` 实测 3 个主题均已存在 | 通过 |
| PW-21 | 全量行为基线：`mvn -o clean test` 全绿且新增行为有对应测试 | `Tests run: 106, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（总耗时 21.4s）；其中 Wiki 相关 6 个类 72 例（23 治理 + 9 并发 + 15 状态机 + 9 capability + 8 差异 + 8 校验）。文档与 ADR 改动后又复跑一次，仍 106/0/0/0 | 通过 |
| PW-22 | 交付物红线扫描（文档改完后复跑） | `check-evidence.cjs` **0 error / 13 warning**，EXIT=0，核对正文引用 252 条、ADR 14 份；13 条 WARN 全是"待产出路径"（F-1/F-2 关闭后才能产出的 `wiki-publication.md`/`wiki-acl.md`/`oidc-session.md`、backlog #20/#26 的关闭证据、P1/P2 设计文档里的规划路径），无一条是现有交付物引用落空。`check-hygiene.cjs` **0 error / 4 warning**，EXIT=0，扫描交付文本 345 个；4 条 WARN 是 `.gitignore` 为本机尚未生成的产物预留的规则 | 通过 |

## 本轮测试暴露并修复的缺陷（不是"顺手重构"，都是代码与契约不符）

| 缺陷 | 位置 | 症状 | 处置 |
| --- | --- | --- | --- |
| 文本块拼接吃掉空格 | `repo/WikiQueryMapper.java`（`findRevisionByNo`/`listSources`/`listSourcesFor`） | `... AS createdAtFROM wiki_...`，31 例里 29 例 `BadSqlGrammar` | 改为显式字符串拼接，编译期可见空格；31 例转绿 |
| 乐观锁冲突把 `If-Match` 那一行当「最新修订」报 | `wiki/WikiWriteTx.java` 4 处 `revisionLost(...)` | §5-3 的 `latestRevisionNo`/`latestBodyDigest12` 给的是旧修订 → 客户端按错的号重试仍失败 | 新增 `WikiQueryMapper.findLatestRevision`（`NULL AS body`，不捞正文），4 处统一改用它 |
| 测试夹具来源文档摘要恒定 | `it/P1WikiTestContext.readyDocument` | 撞 `document.uk_doc_kb_hash_version`，第二份来源直接 DuplicateKey | 摘要改由行 ID 派生 |

## 失败项与责任

本轮无失败断言。未达成项全部是**范围外阻断**，登记如下（详见 `p0/decisions/P1-backlog.md`）：

| 未达成 | 归属 | backlog | 责任人 | 复测日期 |
| --- | --- | --- | --- | --- |
| 真 WeKnora 引擎未得（F-1）→ W2 全部、§13.1 前 3 条硬门槛无法证明 | 阻断 | #1、#23 | 平台运维→Java | 2026-11-06 取镜像，2026-11-13 复跑 |
| 真实 OIDC IdP 未接（F-2）→ 审核用例只能在 test profile 的 dev-subject 上跑 | 阻断 | #24 | 平台运维 | 2026-11-13 |
| 全内网冷启动未复跑（F-3，含 3 个新主题形态） | 阻断 | #2 | 平台运维 | 2026-11-06 |
| 试点库 `kb_p1` 尚未应用 V12–V18（需重启 P1 api 触发 Flyway） | 高 | #26 | 平台运维 | 2026-11-06 |
| P1 上传写路径未写 `family_id`，而 V6 已把它改成 `NOT NULL` | 阻断 | #25 | Java | 2026-11-06 |
| `wiki_review_assignment` 引导 SQL 未进 `p1/compose/README.md` | 中 | #27 | Java＋平台运维 | 2026-11-20 |
| `RETRACTING` 无自环，重复 retract 现在 409（补偿入口在 W2） | 中 | #28 | Java | 2026-11-27 |
| x-error-codes 可产生性只核到全局，未按操作 | 中 | #29 | Java | 2026-11-27 |

## 已知限制（不得当作通过）

1. **引擎侧零验证**：`kb.wiki.sync/retract/dlq` 三个主题只有 schema、样例、建题条目与边表，
   没有任何生产方/消费方代码。断言只到「`wiki_sync_task` 停在 `PENDING`、`outbox_event` 无 `kb.wiki%` 行」，
   这条是**"未接线"的正向证据**，不是同步能力的证据。
2. **硬门槛未重跑**：越权 0 例、断网全链路、退避生效、重复索引 0 这四条必须在 F-1/F-2/F-3 关闭后
   在真引擎与真实身份域上重跑（W3 的 21/22 号验证脚本尚未创建，见 backlog #23）。
   本轮计数全部来自 stub 引擎 + test profile 夹具身份，按红线 4 不得引用为生产承诺。
3. **新鲜度派生位不写**：`freshness` 只有默认值 `FRESH`，无调度器（W3-01）；
   用例显式断言 `FRESH`，就是「不许假装标过 STALE」。
4. **纠错工单/提醒/通知只落表**：`correction_case`、`wiki_reminder`、`notification`、`wiki_staleness_run`
   四表由 V15 建好，端点与状态迁移在 W3；本轮没有一行数据经过它们。
5. **可产生性粒度不足**：24 号门禁核对「错误码在码表/枚举/OpenAPI 三方一致 + 全局可产生」，
   未按**单个操作**核 x-error-codes；因此 `getWikiPage` 里 detail 路径产不出的 `INVALID_STATE_TRANSITION`
   不会报警（backlog #29）。
6. **前端未动**：§10 与 `p1/web` 骨架属 W3，Q-2 未拍板。
7. 试点栈未启停：本次没有 `docker compose up/down`，`p1/compose` 只在契约与配置目录层面被核对。
8. **真实上传端点未被任何测试覆盖**（P1 遗留）：`DocumentWriteTx:76` 用的
   `PipelineCommandMapper.insertDocument` 列清单里没有 `family_id`，而 `V6` 第 20 行已把它
   `MODIFY … NOT NULL` 且无默认值；`src/main/java` 全库搜不到 `family_id` 的写入，
   P1/P2 的集成测试都是直插 SQL 建文档，所以这条路**从未被执行**。
   本条只有代码走查证据、**未实测**（实测要在真实上传端点上跑，backlog #25），
   不得据此宣称"上传已坏"，只能宣称"上传路径缺列且无测试覆盖"。

## 复现要点

- 集成测试要求 `kb_p1_test` 已存在且 `kb_p1_ddl`/`kb_p1_app` 权限按 `p1/compose/README.md` 第 4 节配好，
  否则 `FlywayMigrationTest` 与 `DatabasePrivilegeGuard` 直接失败。
- 时间驱动组件被显式停住（`auto-startup=false`、泵间隔 1h），所以任何「没有事件」的断言
  都来自配置注入而非引擎行为，改这些参数会让 PW-16 的断言失去意义。
