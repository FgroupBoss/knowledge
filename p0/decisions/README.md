# P0 决议索引

每条 ADR 都必须能被至少一份报告推翻或证实；没有证据的不写 ADR，写"待验证"。

| 编号 | 主题 | 状态 | 证据 |
| --- | --- | --- | --- |
| ADR-001 | 依赖与镜像按摘要锁定，验证环境不联网拉包 | 已采纳 | `compose/image-manifest.txt`、`offline-drill.md` OF-01 |
| ADR-002 | 权限权威在 MySQL，Redis 只是可重建加速件 | 已采纳（含只写不读缺陷，见 backlog #5） | `authz-matrix.md`、`retrieval-acl.md`、`fault-drill.md` FD-01~FD-04 |
| ADR-003 | 候选引擎 WeKnora 不可得，P0 用 stub 并限定结论 | **阻断项** | `PendingWeKnoraAdapter`、`quality-baseline.md` 口径说明 |
| ADR-004 | 入库走事务 Outbox + 幂等账本 + 退避 + 死信 | 已采纳（**退避未生效，见 backlog #18**） | `ingest-run.md`、`fault-drill.md` FD-05~FD-08、FD-10~FD-12、FD-09（不通过） |
| ADR-005 | 数据模型与迁移约定（无外键、UUID、UTC、单向迁移） | 已采纳 | `db/README.md`、`11-sync-migrations.sh` 摘要校验 |
| ADR-006 | 上传卡口与证据预算（十种格式、签名校验、扫描件永久失败） | 已采纳 | `ingest-run.md` UP/LIM、`quality-baseline.md` EV-03/04/08 |
| ADR-007 | 审计是同步旁路写，允许"业务成功、审计失败" | **已知缺陷** | `AuditService` 注释、`quality-baseline.md` EV-12 |
| ADR-008 | 无答案问题不拒答，相关性下限待重校准 | **P1 阻断项**（Go-No-Go 例外 E2） | `quality-baseline.md` 拒答段、`quality-raw.jsonl` |
| ADR-009 | 离线取证口径与门户容器化前置 | 已采纳（口径受限） | `offline-drill.md` OF-01~OF-10 |
| ADR-010 | Wiki 正文权威在 MySQL，引擎索引与对象存储都不是真相（P2 D-01） | 已采纳，备份路径未复跑 | `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-02/13/14 |
| ADR-011 | 页面态/修订态/同步态三轴正交，W1 只接前两轴（P2 D-02） | 已采纳（SYNC 轴只有边表） | `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-04/05/06/07/08/09/16 |
| ADR-012 | 治理与审核能力用指派表 + capability，不新增角色枚举（P2 D-05） | 已采纳（W1，无指派端点） | 同上报告 PW-10/11；`wiki/WikiCapabilities.java` |
| ADR-013 | 编辑冲突显式失败、差异由服务端算，拒绝自动合并（P2 D-07） | 已采纳 | `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-06/07/13 |
| ADR-014 | `p1/contracts` 是 pilot 侧契约事实源，门禁扩展不放宽（P2 §11） | 已采纳（有已知缺口 #29） | `p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-04/18/19/20 |

另附：

- `P1-backlog.md` — 29 项，全部带关闭证据、责任人与目标日期；P0 期间脚本造成的运行状态也记在末尾。
- `Go-No-Go.md` — 硬门槛与目标门槛逐条判定，含例外签署栏。
- P2 的实现状态与未验证边界见 `outputs/P2落地实现说明.md`；P2 的实测证据落 `p1/evaluation/reports/`。
