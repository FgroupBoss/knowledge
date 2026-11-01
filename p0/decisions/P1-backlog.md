# P1 backlog（P0 结束时登记，全部带验收方式）

排序规则：先补"结论有洞"的阻断项，再补"能力没证"的高优先项，运维硬化类放中低。
每一项都必须能用一条可复跑的证据关闭，不接受"改完了"。
编号一经登记不再变动（其他 ADR 与报告按编号引用），新增项按级别插入对应分组末尾，所以编号不连续递增。
关闭证据里**还没产出的文件只写文件名**（如 `wiki-publication.md`）并注明放置目录：写全路径会被 `check-evidence.cjs`
判成"现有交付物的引用落不了地"（ERROR），只写文件名则记为待产出（WARN）。

| # | 级别 | 事项 | 来源 | 关闭证据 | 责任人 | 目标 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 阻断 | 拿到 WeKnora 可用镜像/离线包，按官方 API 实现 `KnowledgeEnginePort` 六个方法 | ADR-003 | `07-verify-retrieval.py` 29/29 在真引擎上复跑 + `09-run-eval.py` 12/12 | 平台运维→Java | 2026-11-06 取镜像<br>2026-11-13 复跑 |
| 2 | 阻断 | 门户容器化后进 `p0-backend`，用 `docker-compose.offline.yml` 跑全内网冷启动 | ADR-009 | `10-verify-offline.py` 在"依赖全 internal"形态下 10/10 | 平台运维 | 2026-11-06 |
| 3 | 阻断 | 拒答能力：阈值在生产语料上重校准 + 状态能表达拒答（`NO_EVIDENCE`/`REFUSAL`）+ 缺依据不落引用 | ADR-008 | 无答案集 10 条全部拒答且 `citations=0`；可答集 Recall@5 不降 | Java＋数据＋业务 | 2026-11-13 |
| 4 | 阻断 | 审计写入事务化（outbox 投递），审计缺失变成可告警事件 | ADR-007 | 故障注入：业务成功但审计必存在；`DENY` 计数与越权用例互证 | Java＋安全 | 2026-11-20 |
| 18 | 阻断 | **指数退避未生效**：`claimTask` 不看 `next_run_at`，暂时性故障几秒内被打成死信（契约下界 60s） | ADR-004 / FD-09 | `13-fault-drill.py` FD-09 通过：首跳等待 ≥`backoff-base-ms×2`，且注入 30s 存储抖动期间**零死信**（现网会零人工介入自动恢复） | Java | 2026-11-06 |
| 23 | 阻断 | Wiki 的三条硬门槛（未发布 0 命中、发布幂等、撤稿时限内不可检）**只在真引擎上算数**；`p1/scripts/` 下的 21/22 号验证脚本（发布门槛、Wiki 检索鉴权）尚不存在 | P2 §13.1 / ADR-011 / F-1 | 两个脚本存在并按 `wiki-publication.md`、`wiki-acl.md`（p1/evaluation/reports 下）出报告：重复投递 `engine_visible_count=1`、草稿 0 命中、撤稿时限内 0 命中 | Java＋平台运维 | F-1 关闭后 2026-11-20 |
| 24 | 阻断 | 真实 OIDC IdP 未接（F-2）：审核与治理用例只能在 test profile 的 `kb.auth.dev-subject-enabled` 身份上跑，会话与 Cookie 域未实测 | P2 §1.3.1 F-2 / ADR-012 | 关掉 dev-subject 后 `WikiGovernanceIntegrationTest` 换真实 subject 仍全绿，且 `oidc-session.md`（p1/evaluation/reports 下）含登录/登出/会话失效与审计计数 | 平台运维＋Java | 2026-11-13 |
| 25 | 阻断 | **P1 上传写路径会失败**：`DocumentWriteTx:76` → `PipelineCommandMapper.insertDocument` 的列清单没有 `family_id`，而 V6 已把它 `MODIFY … NOT NULL` 且无默认值；`main/java` 里搜不到任何 `family_id` 写入，P1/P2 测试都是直插 SQL 绕开了这条路 | 代码走查（本轮 P2-W1 夹具改造时发现）/ `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` 已知限制 8 | 新增一条**走真实上传端点**的集成测试并全绿（当前不存在，故缺陷未被任何测试暴露）；修复后 `mvn -o clean test` 与 `06-verify-ingest.py` 同时通过 | Java | 2026-11-06 |
| 5 | 高 | ACL 决策缓存**只写不读**（水位键仅在权限变更时写，`hits` 恒为 0） | FD-02 / ADR-002 | 压测报告里 `hits>0` 且 P95 有可比下降；fail-close 用例仍全通过；FD-02 补断言"清空后第二次问答 hits 上升" | Java | 2026-11-13 |
| 6 | 高 | DDL/DML 账号拆分，Flyway 用独立 DDL 账号，应用账号去掉 `ALTER/CREATE/DROP` | ADR-005 | 以应用账号执行迁移必须失败；`01-stack-up.sh` 全链路仍通过 | 平台运维 | 2026-11-20 |
| 7 | 高 | 生产语料复测：真实格式分布、分片大小、`context-chunks`、`P0_MODEL_MAX_PROMPT_CHARS` 重估 | ADR-006 | 新一轮 `quality-baseline.md`（语料清单+摘要入库） | 数据＋业务 | 2026-11-20 |
| 8 | 高 | 扫描 PDF／图片文档的 OCR 路线（做还是不做，业务签字） | ADR-006 EV-04 | 明确 ADR：支持则给出准确率与吞吐；不支持则写进使用边界 | 业务＋Java | 2026-11-20 |
| 9 | 高 | 网关层限流与并发闸（应用内计数限流在多实例下会失效）；SSE 客户端断连即取消推理 | 契约评审 | 两实例并跑时限流总额正确；断连后 GPU 占用立即回落 | 平台运维＋Java | 2026-11-27 |
| 26 | 高 | **试点库 `kb_p1` 还没应用 V12–V18**：本轮迁移只在 `kb_p1_test` 上以 DDL/App 分账号复跑，P1 api 容器未重启触发 Flyway | `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-01/口径 | 重启 P1 栈后 `flyway_schema_history` 最大版本 = 18、`/actuator/health/readiness` 含 db 为 UP、`bash p1/scripts/30-sync-migrations.sh` 退出码 0 | 平台运维 | 2026-11-06 |
| 10 | 中 | 孤儿对象自动对账与清理（先 `document`/桶双向对账，再按保留期清理，操作全审计） | ADR-004 | `12-backup-restore.sh` 之外新增对账脚本，孤儿数 0 或全部有处置记录 | Java | 2026-11-27 |
| 11 | 中 | 跨机灾难恢复演练：备份写独立介质、加密与不可变保留，实测 RPO/RTO | `backup-restore.md` 已知限制 | 在第二台机器上恢复并完成一次问答；给出实测 RPO/RTO | 平台运维 | 2026-12-04 |
| 12 | 中 | `app_user.user_code` 由全局唯一改为租户内唯一（含 V4 迁移与夹具改造） | ADR-005 | 两租户同名 user_code 可共存；越权矩阵仍 0 例 | Java | 2026-12-04 |
| 13 | 中 | 多实例租约竞争与删除 Saga 并发实测（两个 worker 抢同一任务） | `fault-drill.md` 未覆盖段 | 双实例下 FD-07/FD-08 复跑，无重复索引、无互相覆盖 | Java | 2026-12-04 |
| 14 | 中 | 故障注入补齐：磁盘写满、半容器网络分区、MySQL 主键冲突风暴 | `fault-drill.md` | 三类注入各有断言与报告条目 | 平台运维 | 2026-12-11 |
| 15 | 中 | 引擎索引重建时长 SLA：全量重建耗时与在线度量化（索引是派生物，必须知道重建代价） | `backup-restore.md` | 给定语料规模下重建耗时曲线 + 运维手册里的允许窗口 | 平台运维＋业务 | 2026-12-11 |
| 21 | 中 | `kb.ingest.reconcile.v1` 是孤儿配置：`KbProperties.Ingest.topicReconcile` 与 `application.yml` 有该项，但无生产方、无消费方、`03-topics.sh` 也不建题 | 代码走查 / `check-evidence.cjs` | 二选一并留痕：接线则补 schema＋建题＋对账读数；不接线则删配置项与 compose 变量，`04-check-contracts.py` 通过后无残留 `topic-reconcile` | Java | 2026-11-27 |
| 22 | 中 | P1 的 `KB_*` 配置项（引擎适配、审计主题、会话/Cookie、能力探测）不在 `p0/contracts/config-catalog.md` 的三层同名核对内，目录只冻结在 P0 的 `P0_*` 命名域 | 契约评审 / `p1/contracts/weknora-adapter.md` | 新增 P1 配置目录（或在 04 校验里并入 P1 变量集），逐条写明"环境变量 → Spring 属性 → `@DefaultValue`"，漂移时 `04-check-contracts.py` 非零退出 | Java＋平台运维 | 2026-11-27 |
| 27 | 中 | `wiki_review_assignment` 的引导 SQL 没写进 `p1/compose/README.md`（§9 声称有，实际只有表定义），W1 无指派端点，运维只能猜 | ADR-012 / P2 §9 | README 有可直接复制的引导 SQL（列与 `V12`/`V13` 逐字段一致），且与 `P1WikiTestContext.assignReviewer` 的插入语句等价（同 scope_type/scope_id 语义） | Java＋平台运维 | 2026-11-20 |
| 28 | 中 | `RETRACTING` 期间重复 `retract` 吃 409：契约 E5 的前置态只有 `PUBLISHED`，没有自环，补偿入口"重新发起撤稿"在 W1 无法执行 | `p1/contracts/state-machine-wiki.md` §2/§6 / ADR-011 | 二选一并留痕：补 `RETRACTING → RETRACTING` 自环（幂等重放，带负向用例）**或**在契约里显式写明"补偿只能走 W2 的补偿任务"并把 409 的 `reason_code` 固定；两种都要有对应集成测试 | Java | 2026-11-27（与 W2 同批） |
| 29 | 中 | x-error-codes 的"声明必须可产生"只核到**全局**：`getWikiPage` 声明了 detail 路径产不出的 `INVALID_STATE_TRANSITION`，24 号门禁不报警 | 契约评审 / ADR-014 已知缺口 | `24-check-contracts-p1.py` 按**每个操作**核 x-error-codes 与该操作调用链上的码，且 `--selftest` 有一条"删掉某操作唯一的码"的突变用例；改坏必非零退出 | Java | 2026-11-27 |
| 16 | 低 | 单元测试基线：`ErrorCode` 永久/暂时二分、`Chunker` 偏移与重叠、`UploadValidator` 签名矩阵、`PromptBuilder` 预算、`EngineConfig` 公网地址拒绝 | P0 只有端到端 | `mvn -o test` 通过并纳入 `04-check-contracts.py` 之后的 CI 步骤 | Java | 2026-11-27 |
| 17 | 低 | SSE `Last-Event-ID` 恢复语义目前只冻结契约未压测（重连是否重复计费/重复生成） | 契约评审 | 断线重连用例：不重复生成、事件序号连续 | Java | 2026-12-11 |
| 19 | 高 | Ollama 模型引用按摘要固定：`ollama cp` 生成不可变本地 tag（如 `nomic-embed-text:p0-0a109f4`），启动时校验实际加载摘要与 `image-manifest.txt` 一致 | Go-No-Go E4 / ADR-001 | 摘要不一致时应用拒绝启动；`02-image-manifest.sh` 与运行时校验互证；改模型后必须触发一次全量索引重建 | 平台运维＋Java | 2026-11-06 |
| 20 | 高 | P1 增量主题 `kb.audit.v1` 无 JSON Schema 与样例载荷，`04-check-contracts.py` 只核对 P0 侧 `Events`，改审计事件字段不会报警（审计载荷字段清单目前只在 `p0/contracts/kafka/README.md` 的文字里） | 契约评审 / `check-evidence.cjs` | 新增 `audit-event.v1.schema.json` + `samples/`，`04-check-contracts.py` 纳入该主题并在故意改字段名时非零退出；`P1CoreIntegrationTest` 断言载荷键集与 schema 一致 | Java | 2026-11-13 |

## P0 遗留的运行状态（避免下一轮误判）

- 门户端口已从 18080 迁到 **127.0.0.1:18180**；`ollama serve` 在宿主 :11434 常驻。
- 交付时的运行状态（2026-11-01 收尾重启）：由 `07-run-app.sh` 启动，只额外注入
  **`P0_OFFLINE_ENFORCED=true`**，其余取仓库默认（`P0_CHAT_RATE_PER_MIN=30`、
  `P0_MODEL_MAX_PROMPT_CHARS=4096`、`p0.engine.score-threshold=0.18`、`P0_ENGINE_PROVIDER=stub`）。
  `07-run-app.sh` 本身只注入连接类变量与夹具开关，性能类参数一律来自调用方环境——
  任何人复现指标前必须先确认这三个变量的实际取值。
- 本轮为跑完 130 条问题集，评测那一轮显式注入了 `P0_CHAT_RATE_PER_MIN=400`、`P0_MODEL_MAX_PROMPT_CHARS=6000`
  放宽默认值；正式环境回到默认，任何指标对比必须注明这一点（已写进 `quality-baseline.md` 口径段）。
- 演练后的库内规模（2026-11-01）：workspace 29、knowledge_base 48、document 295（其中 4 份 FAILED）、
  `ingest_task` 死信 3 份、engine_chunk 1427、citation 5139。FAILED 与死信是故障注入留下的证据，
  **不要清理**——清了 FD-10/FD-11 的复跑基线就没了；下一轮演练按 `dlqCount` 的**增量**判定，不按绝对值。
- 评测与夹具会持续累积行，恢复演练按行数对账时以当轮快照为准（`12-backup-restore.sh` 每次都新建时间戳目录）。
- 备份快照留在本机 `p0/.tmp/backup/`（约 14MB，含原件镜像，已被 `.gitignore` 排除，不随交付物走）。
- 本机与项目无关的容器（`zentao`、`filehub-*`）未被任何 P0 脚本触碰。
