# P0 交付包：本地知识库技术验证

P0 只回答一件事：**这套选型在这台机器上能不能真的跑起来、跑对了没有、出了事能不能恢复**。
业务结论在 `outputs/` 的方案文档里，本目录是可执行的验证与它的证据。

## 这个目录里有什么

| 位置 | 内容 | 谁需要它 |
| --- | --- | --- |
| `outputs/P0专项工作方案-本地知识库.md` | P0 计划本体与验收门槛 | 评审起点 |
| `p0/decisions/` | 9 份 ADR + 索引 + P1 backlog（19 项）+ Go/No-Go 决议 | 每条取舍的理由、代价与复测日期 |
| `p0/compose/` | 固定 tag+digest 的基础设施编排、离线叠加文件、镜像清单 | 环境复现 |
| `p0/contracts/` | OpenAPI、Kafka JSON Schema、状态机、错误码、配置项目录 | 前后端与运维的共同口径 |
| `p0/db/migration/` | Flyway 迁移的可评审副本（与执行源逐文件 SHA-256 核对） | DBA |
| `p0/portal-poc/` | Spring Boot 门户（服务端鉴权、入库流水线、检索问答、引擎适配） | 代码评审 |
| `p0/scripts/` | 顺序编号的可执行验证脚本，产出 `evaluation/reports/` 里的报告（`04` 只给退出码，非 0 即契约漂移） | 复现全部结论 |
| `p0/evaluation/corpus/` | 本机生成的目标格式语料（含无文字层扫描件），质量基线的输入。**不随交付提交**：由 `08-gen-corpus.py` + `08-gen-office.sh` 就地重生成，交付的是 `corpus-src/office-spec.json` 规格与 `corpus-manifest.csv` 清单（仅文件名+哈希） | 复现基线 |
| `p0/evaluation/reports/` | 7 份结果报告（鉴权/入库/检索/质量/离线/备份/故障） | 验收材料 |

## 复现顺序

前提：Docker Desktop 可用；Ollama 在宿主 `127.0.0.1:11434` 且已拉取 `deepseek-r1:8b` + `nomic-embed-text`；Maven 本地仓库已就位（全程 `-o` 离线）。

```
00-gen-secrets.sh        生成本机口令（幂等，--force 才覆盖）
01-stack-up.sh           起 MySQL/Redis/Kafka/MinIO 并等健康
02-image-manifest.sh     写下镜像清单（tag+digest），后续脚本按摘要复用镜像
03-topics.sh             显式建 Kafka 主题（禁用 auto-create）
07-run-app.sh            宿主机启动门户 :18180，凭据只进环境变量
04-check-contracts.py    契约一致性：Schema/OpenAPI/Java 记录字段互相对得上
05-verify-authz.py       角色 × 资源 × 动作的越权矩阵
06-verify-ingest.py      单文档入库链路端到端
07-verify-retrieval.py   检索与 ACL 的组合语义
08-gen-corpus.py --phase spec   文本类语料 + 办公文档规格
08-gen-office.sh                真产出 docx/xlsx/pptx/pdf（含无文字层扫描件）
08-gen-corpus.py --phase manifest  语料清单 + 题库
09-run-eval.py           质量与容量基线（Recall@5 / 支持率 / TTFT / 吞吐）
10-verify-offline.py     断网演练：内部网络 + 全链路冷启动 + 宿主外连取证
11-sync-migrations.sh    迁移副本回写并核对哈希
12-backup-restore.sh     备份→导入临时库→逐表核对→删临时库
13-fault-drill.py        故障注入：缓存清空、worker 崩溃、依赖停机、重复投递
```

脚本之间存在真实依赖：`09`/`10`/`13` 读 `08` 产出的语料与题库，`04`~`07` 需要门户在跑，
`12`/`13` 会启停容器，所以**最后两步不要在他人正在使用这套环境时执行**。

## 读结论的顺序

1. `p0/decisions/Go-No-Go.md` — 硬门槛与目标门槛逐条对照，未达标项的责任人与复测日期。
2. `p0/evaluation/reports/` — 每条结论的原始断言与证据。
3. `p0/decisions/ADR-*.md` — 对应取舍为什么这么定、代价是什么。
4. `p0/decisions/P1-backlog.md` — P0 明知没做但必须带着走的事项。

## 边界（先读这段，再引用任何数字）

- 引擎侧验证的是**我们自己的检索/索引契约**（MySQL 支撑的融合检索实现），不是 WeKnora 本体；
  换引擎必须重跑 `07`/`09`/`13`，见 `ADR-003`。
- 质量基线是在**本机生成的目标格式语料**上得到的，不是生产语料；`ADR-008` 记录了拒答能力未达标。
- 断网演练证明了容器无公网路径与门户无云端回退，但门户进程跑在宿主机，
  跨机灾难恢复与容器化编排下的取证口径未验证，见 `ADR-009`。
- 所有数字来自单机、单租户串扰为零的实验环境，不构成生产容量承诺。

## 不入库的东西

`.gitignore` 已排除：`compose/secrets/`、`compose/.env`、`.tmp/`（备份快照与演练中间产物）、
`quality-raw.jsonl`（含语料原文）、`target/`、`cp.txt`。
交付物里不应出现任何口令、对象存储 key、提示词全文或文档正文——报告一律只写哈希与计数。
