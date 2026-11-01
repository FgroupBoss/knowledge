# 变更类型 Playbook

每种变更列三件事：**必须同步的面**（漏一项＝未完成）、**门禁与复跑**、**证据落点**。
路径均相对仓库根。命令细节与端口见 `AGENTS.md` §4；规则本身见 §5–§10。

---

## 1. 新增/修改 REST 端点

- 同步面：`p0/contracts/openapi.yaml`（先改契约）→ `p1/portal/.../api/XxxController.java` + `Dto.java` → 鉴权（`AuthorizationService` 的角色位与文档级 ACL）→ `common/ApiAdvice` 的错误映射 → `p1/compose/nginx/nginx.conf`（新路径要不要走网关、SSE 是否关缓冲）。
- 门禁：`mvn -o clean test` → `python p0/scripts/04-check-contracts.py` → `python p0/scripts/05-verify-authz.py`（新端点必须进越权矩阵，否则视为未验）。
- 证据：矩阵新增行写进 `p0/evaluation/reports/authz-matrix.md`；契约变更属破坏性时开新 ADR。
- 陷阱：`jackson.default-property-inclusion: non_null` 会吃掉 `null` 字段；CSRF 与同源 Cookie 会话是 P1 的认证口径，不能再引入 `X-P0-Subject` 类测试身份头。

## 2. 改 Kafka 事件字段 / 新增主题

- 同步面：`p0/contracts/kafka/*.schema.json`（向后兼容、新增可选字段、`schemaVersion` 递增）+ `kafka/samples/*.json` → `p1/portal/.../pipeline/Events.java` record 字段名与顺序 → `pipeline/OutboxPublisher` 的 header 写入 → **主题必须登记进** `p0/contracts/kafka/README.md` 主题清单 → `p0/scripts/03-topics.sh` 与 `p1/compose/README.md` 的建主题命令（禁 auto-create）。
- 门禁：`04-check-contracts.py` → `bash p0/scripts/03-topics.sh` → `06-verify-ingest.py`（重复投递与幂等）。
- 证据：`ingest-run.md` 的重复投递段；分区键或语义变化写 ADR。
- 陷阱：`04` 只读 **P0 侧** `com.acme.kb.p0.pipeline.Events`，改 P1 record 时它不报警 —— 人工逐字段对照 schema；空值不写 header。

## 3. 改文档状态机 / 失败语义

- 同步面：`p0/contracts/state-machine.md`（迁移表 + 每条失败的分类列）→ `document/DocumentWriteTx`、`pipeline/IngestProcessor`、`PipelineService.claim` 的前置态校验 → `ErrorCode.permanent()` 归类 → **负向用例**（非法迁移必须 409）。
- 门禁：`mvn -o clean test`（`P1CoreIntegrationTest`）→ `06-verify-ingest.py` → `13-fault-drill.py`（需用户同意时机）。
- 证据：`fault-drill.md` 对应 FD 编号；删除 Saga 变化要同时更新补偿说明。
- 陷阱：删除失败绝不回 `READY`、绝不进重试主题；`UPDATE ... WHERE status = 前置态` 不能省。

## 4. 新增/改错误码

- 同步面：`common/ErrorCode.java`（HTTP 状态 + `permanent()` 二分）→ `p0/contracts/error-codes.md` 码表（触发点写到类:场景）→ `openapi.yaml` 的 `ApiError.code` 枚举 → 客户端动作列。
- 门禁：`mvn -o clean test`（`ErrorCodeTest`）→ `04-check-contracts.py`。
- 证据：码表更新即证据；产生不了的码要**删除**（历史上删过两个）。
- 陷阱：跨租户命中对方 ID 时响应降级 404，`CROSS_TENANT_ACCESS` 只写审计；缓存不可用不是错误，所以没有 `CACHE_UNAVAILABLE`。

## 5. 新增配置项 / 改默认值

- 同步面：`config/KbProperties.java`（P1）或 `P0Properties` → `application.yml` 的 `${VAR:default}` → `p0/contracts/config-catalog.md` 三层同名对齐表（含影响面与 P1 归属）→ `p0/compose/.env.example` 或 `p1/compose/.env.example` → 涉及性能参数时同步 `p0/README.md` 的"复现前必须确认实际取值"段。
- 门禁：`mvn -o clean test` → `04-check-contracts.py` → 启动一次冒烟（`07-run-app.sh` 或 P1 compose）。
- 证据：配置目录更新；改了并发/预算类默认值要在报告里注明口径变化。
- 陷阱：池大小与模型并发上限互相牵制，改一个必须看另一个；凭据类一律支持 `*_FILE`。

## 6. 数据库迁移（加表/加列/改类型）

- 同步面：`p1/portal/src/main/resources/db/migration/Vn__p1_*.sql`（**只追加**，禁止改历史）→ `repo/*Mapper.java` 的列与显式 `AS` 别名 → `p0/db/README.md` 迁移序列表（若动 P0 副本）→ `11-sync-migrations.sh` 核对哈希 → DDL/DML 账号权限（`p1/compose/README.md` §1）。
- 门禁：`mvn -o clean test`（`FlywayMigrationTest` 跑 V1–Vn 全量）→ `bash p0/scripts/11-sync-migrations.sh` → `12-backup-restore.sh`（结构大改时，需确认时机）。
- 证据：迁移版本号与校验和；`FlywayMigrationTest` 用例数写进落地说明。
- 陷阱：主键 `CHAR(36)`、无外键、`DATETIME(3)` UTC；审计 `metadata_json` 必须 `MEDIUMTEXT`；参与哈希的时间要 `truncatedTo(MILLIS)`。

## 7. 权限与 ACL

- 同步面：`authz/AuthorizationService`（角色位 + 密级 + 文档级 ACL）→ `AclCacheService` 的缓存键与 `acl_version` 递增点 → `auth/ActorResolverFilter` 与 `IdentityService` → `engine/*` 的候选复核与 `EngineAclSnapshot` → `state-machine.md`/`error-codes.md` 相关行。
- 门禁：`mvn -o clean test` → `05-verify-authz.py`（角色×资源×动作矩阵，**硬门槛越权 0 例**）→ `07-verify-retrieval.py` → `13-fault-drill.py` 的缓存清空段。
- 证据：`authz-matrix.md`、`retrieval-acl.md`；任何"缓存改变判定结果"的现象直接阻断。
- 陷阱：Redis 异常回源 MySQL，既不放宽也不误拒；旧版本缓存键必须读不到；撤权后历史引用也要拒绝。

## 8. 上传与格式支持

- 同步面：`document/UploadValidator`（扩展名 + MIME + 魔数 + 大小）→ `application.yml` 的 `kb.upload.allowed-*` → `config-catalog.md` → `engine/parsing/MultiFormatDocumentParser` → `contracts/openapi.yaml` 的请求体约束 → 语料生成 `p0/scripts/08-gen-corpus.py`、`08-gen-office.sh`、`corpus-src/office-spec.json`。
- 门禁：`mvn -o clean test` → `08-gen-office.sh` → `06-verify-ingest.py` → `09-run-eval.py`。
- 证据：`ingest-run.md` 的 UP/LIM 段；新格式的准确率与吞吐写进 `quality-baseline.md`。
- 陷阱：原件 SHA-256 与登记值不符一律拒绝索引（永久失败）；无文字层扫描件永久失败，OCR 属 backlog #8，不要顺手"降级支持"。

## 9. 问答、引用与拒答

- 同步面：`chat/PromptBuilder`（预算与连续编号）→ `ChatService.isRefusal` 与状态落库（`ChatWriteTx`）→ `CitationService` 的越界丢弃计数 → `SseSink` 与网关 SSE 配置 → `error-codes.md` 的 `CHAT_NO_EVIDENCE` → 阈值来自校准文件时同步说明。
- 门禁：`mvn -o clean test`（`PromptBuilderTest`、`ChatServiceRefusalTest`）→ `07-verify-retrieval.py` → `09-run-eval.py`（Recall@5 / 支持率 / 拒答 10 条 / TTFT）。
- 证据：`quality-baseline.md`；阈值重校准是 backlog #3 的关闭条件，未达标要写 ADR + 复测日期。
- 陷阱：拒答与无证据**零引用**；引用编号必须与 prompt 内编号一致；断线重连（`Last-Event-ID`）语义尚未压测。

## 10. 引擎适配（WeKnora / stub）

- 同步面：`engine/KnowledgeEnginePort` 六方法 → `WeKnoraAdapter`（路径、超时、租户与 KB 过滤、元数据缺失即丢弃）→ `WeKnoraCapabilityProbe` 与 `ENGINE_ACL_UNSUPPORTED` fail-close → `EngineConfig` 的公网地址拒绝 → `config-catalog.md` 的 `kb.engine.*` → 适配契约文档（当前 `p1/contracts/weknora-adapter.md` **尚未落地**，先建再引用）。
- 门禁：`mvn -o clean test`（`WeKnoraAdapterTest`）→ 拿真镜像后复跑 `07`、`09`、`13`（ADR-003 明确换引擎必须重跑）。
- 证据：能力探测结果 + 真引擎复跑报告；没联调就写"已实现，引擎侧未联调"。
- 陷阱：空 scope 不发请求；`StubKnowledgeEngine` 只留 test profile，不进 pilot 产物。

## 11. 管道、退避与死信

- 同步面：`pipeline/IngestConsumer`（账本→认领→执行→落库→ack）→ `PipelineService.claim` 的 `next_run_at <= now` → `RetryPump` 的确定性 `eventId` → `LeaseHeartbeat` 租约 → `OutboxPublisher` → DLQ 与重放入口 → `state-machine.md` 退避段。
- 门禁：`mvn -o clean test`（租约/心跳/幂等用例）→ `13-fault-drill.py` FD-05…FD-12（**必须含 FD-09**：首跳等待 ≥ `backoff-base-ms×2`，注入 30s 存储抖动期间零死信）。
- 证据：`fault-drill.md` 的 FD 编号；backlog #18 以此关闭。
- 陷阱：库内既有 `FAILED` 与死信是复跑基线，不清理，按 `dlqCount` 增量判定。

## 12. compose / 镜像 / 网络 / 端口

- 同步面：`p1/compose/docker-compose.yml`（角色 `KB_ROLE`、internal 与 ingress 网络、healthcheck）→ `.env.example` → `p1/compose/README.md` 步骤与口令占位 → `nginx/nginx.conf`（TLS、SSE、限速）→ `p0/compose/image-manifest.txt` 或 P1 的镜像摘要登记（**tag + digest，禁 `latest`**）→ `p1/portal/Dockerfile` 基础镜像摘要。
- 门禁：`docker compose config` → `docker build -t kb-p1-portal:1.0.0 ../portal && docker compose --env-file .env up -d` → `curl -k https://127.0.0.1:8443/actuator/health/readiness` → `10-verify-offline.py`（断网口径变化时）。
- 证据：容器实跑读数写进 `outputs/P1落地实现说明.md` §4；`node .../check-hygiene.cjs` 必须无 ERROR。
- 陷阱：容器内用服务名 + 原生端口；只挂 internal 时 `ports:` 不生效；`MSYS_NO_PATHCONV=1` 才能生成证书；`secrets/` 与 `nginx/certs/` 必须在 `.gitignore` 里。

## 13. 验证脚本与报告自身

- 同步面：脚本编号与依赖（`p0/README.md` 复现顺序）→ `p0/scripts/p0_verify_lib.py` 共用逻辑 → 报告字段（环境/版本/镜像摘要/数据集哈希/命令/原始结果/失败项/责任人/复测日期）→ 若新增断言，同步它引用的契约文档那一段。
- 门禁：复跑被改的脚本本身 + `check-evidence.cjs`（新增的文件引用必须存在）。
- 证据：新报告或新段落；脚本改动登记进 `p0/decisions/P1-backlog.md` 末尾的运行状态段（历史上脚本改动会留下运行态副作用）。
- 陷阱：`python` 而不是 `python3`；脚本不打印任何凭据值；只写哈希与计数。

## 14. 纯文档/方案更新

- 同步面：`outputs/` 方案 → 若与契约不一致，**契约为准**，文档改描述；若发现文档对现状的描述与代码不符，登记 ADR 或 backlog，不在文档里悄悄改写。
- 门禁：`node .qoder/skills/kb-dev-workflow/scripts/check-evidence.cjs`（防止引用了不存在的脚本/报告）。
- 证据：文档即证据，但"应当已实现"这类句子一律要落到 backlog。

---

## 收尾自检（所有轨道）

1. 三方面同步齐了吗：契约 ↔ 代码 ↔ 文档/报告。
2. `check-evidence.cjs`、`check-hygiene.cjs`、`mvn -o clean test`、`04-check-contracts.py` 的**真实退出码**是多少，有没有把未跑的写成跑过的。
3. 有没有新增口令、Prompt、正文、`latest` 镜像、未覆盖的 secrets 路径。
4. 新结论有没有对应的 ADR/backlog/报告行。
5. 下一条可复跑的验证是什么（写进交付回复）。
