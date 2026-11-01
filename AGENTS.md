# AGENTS.md — 本地知识库工程（P0 验证 / P1 试点）

本文件是本项目对**所有 agent 与开发者**的权威工程规则。开工前读第 2 节定位事实源，动手前过第 10 节红线。
流程（调研 → 编号清单 → 确认 → 实施 → 门禁 → 证据）不在这里，见项目 skill `.qoder/skills/kb-dev-workflow/`。

**冲突裁定顺序**：冻结契约文件 > 实际代码 > 文档散文；**可复跑的证据**（脚本退出码、报告）> 任何"应当已实现"的描述。
文档里标了`【实现未达成】`的段落是**目标契约**，不是现状，不得据此编码。

## 1. 项目在做什么

内网本地知识库：选型验证（P0，已完成并交付证据）→ MVP 文档问答闭环试点（P1，进行中）。
栈：Java 21 + Spring Boot 3.5.16 + MyBatis + Flyway + MySQL 8 + Redis + Kafka + MinIO + Ollama，全部容器化、**全程离线可复现**。
本项目的核心纪律：**每条结论都要能被一条可复跑的证据推翻或证实**，不接受"改完了"。

## 2. 权威文件地图

| 位置 | 作用 | 何时必读 |
| --- | --- | --- |
| `outputs/P1落地实现说明.md` | P1 实际状态、已修缺陷、**未验证边界** | 任何 P1 编码前 |
| `outputs/P2落地实现说明.md` | **P2 当前做到哪一格**（W0/W1 实况、范围裁定、未验证边界） | 任何 P2 编码前 |
| `outputs/P1技术实现文档-本地知识库.md` | P1 设计；§9 契约与质量门禁、§12 验收清单、附录A backlog 映射 | 定范围、判达标 |
| `outputs/P2技术实现文档-本地知识库.md` | P2 设计；§1.3.1 开工门禁、§11 契约工程、§12 编号任务、§13 验收清单、§14 ADR 计划 | 定 P2 范围、判达标 |
| `outputs/P0专项工作方案-本地知识库.md` | P0 计划本体与验收门槛 | 追溯某道门槛的来源 |
| `knowledge-base-research-and-plan.md` | 市场方案横向对比与选型结论（RAGFlow/WeKnora/MaxKB/Dify/FastGPT） | 被问"为什么这么选" |
| `p0/contracts/` | **P0 契约事实源**：`openapi.yaml`、`kafka/*.schema.json`、`state-machine.md`、`error-codes.md`、`config-catalog.md`；`kafka/` 仍是主题与 schema 的**唯一**一份 | 改 P0 接口/事件/状态/错误码/配置 |
| `p1/contracts/` | **pilot 侧契约事实源**（ADR-014）：`openapi-1.2.yaml`、`error-codes.md`、`state-machine-wiki.md`、`config-catalog-p1.md`、`weknora-adapter.md`（后者未在真机验证故未冻结） | 改 P1/P2 接口、Wiki 状态机、`KB_*` 配置 |
| `p0/decisions/` | ADR-001…014 + `Go-No-Go.md` + `P1-backlog.md`（29 项，全带关闭证据） | 每笔取舍要登记时 |
| `p0/evaluation/reports/` | 7 份证据报告（鉴权/入库/检索/质量/离线/备份/故障） | 引用任何数字前 |
| `p1/evaluation/reports/` | P1/P2 侧实测报告（现有 `p2-wiki-contracts-and-w1.md`）；F-1/F-2/F-3 关闭后的 `retrieval-acl.md`、`oidc-session.md`、`offline-drill.md` **尚未产出** | 引用 P2 数字前 |
| `p0/README.md` | P0 脚本复现顺序与依赖关系、结论阅读顺序 | 跑验证前 |
| `p1/compose/README.md` | P1 部署步骤、角色拆分、账号最小权限、运行约束 | 起 P1 栈 |
| `p1/portal/src/main/resources/db/migration/` | **P1/P2 迁移执行源**（V1–V18） | 加表改列 |
| `p1/db/migration/` | 上述执行源的**交付评审副本**，经 `p1/scripts/30-sync-migrations.sh` 逐文件 SHA-256 核对 | 评审 P1/P2 DDL |
| `p0/db/migration/` | P0 迁移的**交付评审副本**，经 `11-sync-migrations.sh` 与执行源逐文件 SHA-256 核对 | 评审 DDL |

## 3. 目录边界

| 目录 | 性质 | 规则 |
| --- | --- | --- |
| `p0/` | 验证代码，**不是生产代码** | 不往 P0 加新功能；P0→P1 复用走"复制 → 评审 → 补测试 → 改包名 `com.acme.kb.p1`" |
| `p1/portal/` `p1/compose/` | P1/P2 生产化门户与部署 | 主要编码区 |
| `p1/contracts/` `p1/db/migration/` `p1/scripts/` `p1/evaluation/` | P2 起的 pilot 侧契约、迁移评审副本、P1/P2 门禁与验证脚本、P1/P2 实测报告 | 与 `p0/*` 同权重；副本与执行源必须哈希一致，脚本只扩展不放宽 |
| `outputs/` | 交付方案文档 | 结论与口径写在这里，不放运行日志 |
| `work/` | 一次性草稿脚本 | 不属于交付物，改动无需评审 |
| `.tmp/` `p0/.tmp/` `target/` | 本机可再生产物 | 已被 `.gitignore` 排除，绝不引用其中内容作为结论依据 |

**不得进 pilot 产物**：`StubKnowledgeEngine`（只留 test profile）、`FixtureController` 与测试身份头、验证端点、`P0_ENGINE_LEAK`、dev-subject 开关。

## 4. 环境与命令

本机实测：JDK 21.0.9（`D:\java`）、Maven 3.9.14、Docker 29.4.0、Python 3.12.10、Node 24、Git Bash。

前置：Docker Desktop 在跑；Ollama 宿主 `127.0.0.1:11434` 且已拉 `deepseek-r1:8b` + `nomic-embed-text`；Maven 本地仓库已就位（**一律 `-o` 离线**）。

| 做什么 | 命令 |
| --- | --- |
| P1 编译+测试 | `cd p1/portal && mvn -o clean test` |
| P1 打包 | `cd p1/portal && mvn -o clean package` |
| 起依赖栈（MySQL/Redis/Kafka/MinIO） | `bash p0/scripts/01-stack-up.sh`（内含 00 生成口令、03 建主题） |
| 宿主机跑 P0 门户 :18180 | `bash p0/scripts/07-run-app.sh` |
| 契约漂移检查（**退出码非 0 即漂移**） | `python p0/scripts/04-check-contracts.py`（P0 侧） |
| P1/P2 契约漂移检查 | `python p1/scripts/24-check-contracts-p1.py`，加 `--selftest` 验证校验不是空跑 |
| P1/P2 迁移副本核对 | `bash p1/scripts/30-sync-migrations.sh` |
| 起 P1 栈 | `cd p1/compose && docker build -t kb-p1-portal:1.0.0 ../portal && docker compose --env-file .env up -d` |
| P1 就绪探针 | `curl -k https://127.0.0.1:8443/actuator/health/readiness`（db/redis/kafkaProbe/disk/engineProbe/storage） |
| 迁移副本回写核对 | `bash p0/scripts/11-sync-migrations.sh` |
| 其余验证脚本 | 按 `p0/README.md` 的编号顺序，`05`鉴权矩阵 `06`入库 `07`检索ACL `09`质量基线 `10`断网 `12`备份恢复 `13`故障注入 |

端口：P0 门户 **18180**，P1 api **18181**，gateway **8080**（308 → **8443**）；依赖宿主回环 mysql **13306** / redis **16379** / kafka **29092** / minio **19000**。
**容器内**必须用服务名与原生端口（3306/6379/9092/9000），写宿主映射端口是已发生过的缺陷。

## 5. 契约纪律（冻结件，改动即评审）

| 契约 | 文件 | 变更规则 |
| --- | --- | --- |
| REST | `p0/contracts/openapi.yaml` | 加法优先；删除或改语义需版本化与评审 |
| Kafka | `p0/contracts/kafka/*.schema.json` | 向后兼容；新增可选字段；`schemaVersion` 递增 |
| 状态机 | `p0/contracts/state-machine.md` | 新状态必须补迁移表 **与负向用例** |
| 错误码 | `p0/contracts/error-codes.md` | **声明必须可产生**；产生不了的码要删除 |
| 配置 | `p0/contracts/config-catalog.md` | 环境变量 / Spring 属性 / 代码默认值 **三层同名对齐** |
| DB | 迁移目录 | 单向追加；副本与执行源哈希一致 |
| REST（P1/P2 增量） | `p1/contracts/openapi-1.2.yaml` | 加法优先；操作与 Controller **一一对应**，`x-error-codes` 逐条核 |
| 状态机（Wiki 三轴） | `p1/contracts/state-machine-wiki.md` | 边表 ↔ `WikiStateMachine.EDGES` 逐条比对；新边必补负向用例与实现测试类名 |
| 错误码（P1/P2 增量） | `p1/contracts/error-codes.md` | **声明必须可产生**；新增码必须归类 `permanent()` |
| 配置（P1 增量） | `p1/contracts/config-catalog-p1.md` | 环境变量 / Spring 属性 / `@DefaultValue` **三层同名对齐**，漂移即非零退出 |

三方同步是硬要求：契约文件 ↔ Java（`Dto`、`ErrorCode`、`Events`/`WikiEvents` record、`KbProperties`）↔ 本文档引用的说明。
`04-check-contracts.py` 只覆盖 **P0 侧** `com.acme.kb.p0.pipeline.Events` 与 P0 的 schema/OpenAPI；**改 P1 事件/配置字段时它不会报警**。
P1/P2 侧由 `p1/scripts/24-check-contracts-p1.py` 核对：错误码表 ↔ `ErrorCode`、`config-catalog-p1.md` ↔ `application.yml` ↔ `KbProperties`、
`openapi-1.2.yaml` ↔ Controller 操作与 `x-error-codes`、`state-machine-wiki.md` 边表与审计动作清单 ↔ Java、
`p0/contracts/kafka/*.schema.json` ↔ P1 `Events` record 字段与 `AuditWriter` 载荷键。加 `--selftest` 证伪：**改坏一处必非零退出**，
空跑的检查项直接判失败，不许当门禁。
仍是文字契约、没有自动校验的两处：`p1/contracts/weknora-adapter.md`（真机未联调，backlog #1/#23）与 `kb.ingest.reconcile.v1`
（孤儿配置，backlog #21）。backlog #20/#22 已由这套校验关闭；已知缺口是 `x-error-codes` 的"可产生"只核到**全局码集**、
不核单个操作的调用链（backlog #29），逐操作核之前 `getWikiPage` 这类多余声明不会报警。

## 6. Java 编码与架构约定

- 包分层（p0/p1 同构）：`api`（薄 Controller + `Dto` record）→ `document`/`chat`/`pipeline`/`authz`（编排）→ `*WriteTx`（事务边界）→ `repo`（MyBatis 注解 mapper，**无 XML**）→ `engine`（端口与适配）；`common` 放 `ErrorCode`/`ApiException`/`Hashing`/`Secrets`/`TraceIds`；`config` 放属性与装配。
- 构造注入 + `final` 依赖；DTO/事件一律 `record`；`map-underscore-to-camel-case` 与 `arg-name-based-constructor-auto-mapping` 依赖生效，**SQL 里显式 `AS` 驼峰别名**（照 `TaskMapper.COLUMNS` 的写法），不靠隐式映射。
- 异常：只抛 `ApiException(ErrorCode)`，由 `ApiAdvice` 统一转响应；未预期异常 `INTERNAL_ERROR` + 500，返回体不含异常类名/堆栈。`message` 面向用户，**绝不含**对象键、Prompt、SQL、凭据、对方租户标识；堆栈只进日志（MDC 带 `traceId`、`actor`）。
- 新增错误码必须**同时**进 `ErrorCode` 枚举、`error-codes.md` 码表、OpenAPI `ApiError.code`，并在枚举里正确归类 `permanent()` / 暂时性；跨租户一律降级为 404，`CROSS_TENANT_ACCESS` 只写审计不作响应。
- 方法建议 ≤ 50 行、嵌套 ≤ 3 层、圈复杂度 ≤ 10；用卫语句提前返回，长 `if-else` 换策略对象或映射表。
- 注释只写**为什么**（并发与事务边界、幂等约定、合规约束、历史缺陷）；改逻辑必须同步删改旧注释。不为凑行数写复述型注释。
- 不改与当前任务无关的文件，不"顺手重构"，不为假想需求做抽象。

## 7. 数据模型与迁移（ADR-005）

- 主键 `CHAR(36)` 存 UUID 规范字符串；**无外键**；时间列 `DATETIME(3)` 一律 UTC，Java 侧 `Instant`。
- 参与审计哈希链的时间必须先 `truncatedTo(MILLIS)`（DATETIME(3) 会对第 4 位小数四舍五入 → 链校验偶发失败）。
- `metadata_json` 用 `MEDIUMTEXT`，**不用 JSON 列**（MySQL 会补空格/重排，读回不等于写入字节 → 哈希必错）。
- 迁移只追加，禁止改历史版本；Flyway `baseline-on-migrate=false`、`out-of-order=false`。
- 账号拆分：Flyway 用 DDL 账号 `kb_p1_ddl`，运行账号 `kb_p1_app` 只有 SELECT/INSERT/UPDATE/DELETE；`DatabasePrivilegeGuard` 在 `ApplicationReadyEvent` 校验 `SHOW GRANTS`，越权直接拒绝启动。
- 业务表与引擎镜像表分开；引擎侧字段只用于召回与取证，**权威判定永远回到 MySQL**。

## 8. 事件、状态机与失败语义

- 事件**只由事务 Outbox 产生**（与业务写同事务），不存在"业务已提交但事件丢失"；发布成功但标记失败会产生重复投递，由 `event_consumption` 账本按 `eventId` 挡住。
- 按 `documentId` 分区保证同文档严格有序；header `p0-event-id`/`p0-schema-version`/`p0-trace-id`/`p0-correlation-id`/`p0-actor-id`，**空值不写 header**（读侧靠 `null` 判断"确实没有上下文"）。
- Kafka offset 不是业务状态：登记账本 → 租约认领 → 执行 → 状态落库 → 才 ack（`enable-auto-commit=false` + `manual_immediate`）。
- 状态迁移必须 `UPDATE ... WHERE status = 前置态`，非法迁移返回 `INVALID_STATE_TRANSITION`(409) 并写审计。
- 永久性错误（`DOCUMENT_FORMAT_UNSUPPORTED`/`DOCUMENT_SIGNATURE_MISMATCH`/`VALIDATION_FAILED`/`CROSS_TENANT_ACCESS`/`ACCESS_DENIED`）**不进重试主题**；只有暂时性错误按 `backoff_base_ms × 2^attempts` 进 `kb.ingest.retry.v1`。
- 退避认领靠 `next_run_at <= now`（不靠 sleep）；`RetryPump` 的 `eventId` 用 `UUID.nameUUIDFromBytes(taskId + ":" + attempts)` 保证确定性。
- 删除 Saga 失败**绝不回 `READY`**：置 `COMPENSATION_REQUIRED`，补偿入口是重新发起 `DELETE`；失败不进 `kb.ingest.retry.v1`（重放会把正在下线的文档重新索引出第二份有效索引）。
- 拒答即零引用：`REFUSED` / `NO_EVIDENCE` 只写回答、状态与审计，**不写 `chat_citation`**；引用编号必须与送入 prompt 的候选编号一致，越界引用丢弃并计数。

## 9. 安全默认值：fail-close

- 缓存异常 → 回源 MySQL，不把"缓存不可用"当"无权限"，也不因 Redis 故障放宽判定；权限权威只在数据库，Redis 是可重建加速件（ADR-002）。
- ACL 变更递增 `knowledge_base.acl_version`，缓存键带版本号，旧键永远读不到；失效按指针精确删除，不扫描。
- 引擎能力探测发现不支持租户隔离 → `ENGINE_ACL_UNSUPPORTED` 拒绝就绪，不降级放行；**空 scope 不发请求**（无范围 ≠ 全库）。
- 缺 `KB_OIDC_ISSUER`、缺 secret 文件、口令为空：一律**拒绝启动**，绝不退化成匿名或默认口令。
- 凭据只从环境变量或 `*_FILE`（Docker secrets）进入进程，读取处统一 `strip()`（CRLF 会让 MinIO 签名多一个 `\r`）；脚本绝不打印任何值。
- 网络两层：`kb-p0_p0-backend` 为 **internal**（容器无外网路由），只有 api/gateway 挂 `kb-p0_p0-ingress` 才发布端口；只挂 internal 时写 `ports:` 不生效。
- 会话 `kb_session` cookie `HttpOnly` + `Secure`，必须经 8443；`server.error.include-*` 全部关闭；`management.info.env.enabled=false`。

## 10. 红线（无条件遵守）

1. **口令、对象存储 key、Prompt 全文、文档正文绝不进**代码、契约、文档、报告、日志或提交；报告只写哈希与计数。
2. 镜像必须 **tag + digest** 锁定，禁止 `latest`、禁止未固定摘要（ADR-001）；模型引用按摘要固定并在启动时校验。
3. 不以"赶进度"换取降低**硬门槛**（越权 0 例、断网全链路、退避生效、审计完备、重复索引 0）；未达标只能落成带责任人/缓解/复测日期的 ADR，或走 Go/No-Go 例外签署。
4. 不引用不可复跑的数字；不把合成语料结论当作生产承诺，引用指标必须注明口径（环境、注入的性能参数、语料来源）。
5. **不清理演练证据**：库里的 `FAILED` 文档与死信是 FD-10/FD-11 的复跑基线；下一轮按 `dlqCount` 增量判定。
6. 破坏性或影响他人环境的动作（`12-backup-restore.sh`、`13-fault-drill.py`、`10-verify-offline.py` 会启停容器/断网）执行前先向用户确认时机。
7. 生成 `.ps1` 若必须含中文，用纯 ASCII 或 UTF-8 带 BOM（PowerShell 5.1 按 GBK 解析会整脚本失败）。
8. 删除文件/分支、`git reset`、覆盖历史、改 CI 之类不可逆操作，先确认。

## 11. 证据与文档约定

- **每条 ADR 必须能被至少一份报告证实或推翻**；没有证据的不写 ADR，写"待验证"。
- `P1-backlog.md` 每项必须带**关闭证据 + 责任人 + 目标日期**；**编号一经登记不再变动**（其他 ADR 与报告按编号引用），新增项按级别插入对应分组末尾，所以编号不连续。
- 每份报告必须含：环境/版本/镜像摘要、数据集哈希、执行命令、原始结果、失败项、责任人、复测日期。
- 写作风格：简体中文，表格优先，结论先行，每条断言可追溯；"目标契约"与"当前实现"分开写，绝不混写成一句。
- 关闭 backlog 项的表述格式：`已实现并验证 → 证据路径`，或 `已实现，引擎侧未联调 → 待复测项`（照 `outputs/P1落地实现说明.md` §1）。

## 12. 完成的定义（DoD）

一次改动只有全部满足才算完成，否则如实报告缺项：

1. `mvn -o clean test`（P1）全绿，新增行为有对应测试，**不只靠结构校验通过充数**；
2. `python p0/scripts/04-check-contracts.py` 退出码 0；
3. 受影响的契约文件、`config-catalog.md`、错误码表、状态机**同步到位**；
4. 受影响的验证脚本已复跑，或明确写出"未复跑，风险 X"；
5. 结论落到证据：`p0/decisions/`（ADR/backlog/Go-No-Go）或 `outputs/`；
6. 无新增密钥、正文、Prompt 泄漏；`.gitignore` 覆盖新增的本机产物；
7. 门禁脚本可跑：`node .qoder/skills/kb-dev-workflow/scripts/check-evidence.cjs` 与 `check-hygiene.cjs` 无 ERROR。

## 13. 本机已知坑

- `python3` 是 Microsoft Store 别名，会失败；脚本用 **`python`** 调用。
- Git Bash 会把 `/CN=...`、`--format '{{...}}'` 之类参数当路径重写：openssl 证书命令前加 `MSYS_NO_PATHCONV=1`。
- `mvn` 不加 `-o` 会尝试联网拉包并失败（验证环境不联网，见 ADR-001）。
- Jackson 配 `default-property-inclusion: non_null`：期望出现 `null` 字段的契约测试要显式处理。
- `p1/portal/target/`、`p0/.tmp/` 里有旧构建产物，排查时先确认时间戳，别拿旧 `application.yml` 下结论。

## 14. 版本控制现状

本目录**已是 git 仓库**（2026-11-01 实测）：分支 `main`，远程 `origin = https://github.com/FgroupBoss/knowledge.git`。
`.gitignore` 从这一起是**生效规则**，不再只是交付纪律的声明。

- 实测排除生效（`git check-ignore -v`）：`p1/compose/secrets/`、`p1/compose/.env`、`p1/portal/target/`、`p0/.tmp/`、`.idea/`。
  `git ls-files` 里唯一的 `.txt` 是 `p0/compose/image-manifest.txt`（只有镜像 tag + digest，无凭据）。
  **提交前必须复跑这两条核对**，凭据一旦进过历史就不是"删掉就行"（红线 1）。
- 迁移副本（`p1/db/migration/`、`p0/db/migration/`）与执行源都在版本控制内，哈希核对仍跑 `11`/`30` 号脚本。
- 历史只有两条提交时，`git log`/`git diff` 能提供的兜底非常有限：**改契约或迁移文件前仍先复制需覆盖的版本**。
- 推送、建分支、`git reset`、覆盖历史、改 CI 之类对外可见或不可逆的动作，**每次都要用户明确确认**（红线 8），
  一次授权只覆盖那一次；不要因为"上次推过"就自行 push。
