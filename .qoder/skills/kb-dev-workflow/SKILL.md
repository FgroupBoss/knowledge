---
name: kb-dev-workflow
description: 本地知识库工程（P0 验证 / P1 试点）的开发工作流：调研与影响面 → 带编号任务清单 → 用户确认 → 实施 → 门禁复跑 → 证据落地。改 Spring Boot 门户（p1/portal）、冻结契约（openapi/Kafka schema/状态机/错误码/配置目录）、Flyway 迁移、鉴权与 ACL、入库管道、compose 部署、Python 验证脚本时使用；也用于关闭 P1-backlog 项、登记 ADR、复跑并落证据。触发词：改门户/加接口/改契约/改状态机/跑门禁/复跑验证/关闭 backlog/落 ADR/部署 P1。
argument-hint: <要做的改动一句话描述>
---

# 本地知识库开发工作流

## Overview

把本项目既有的**证据纪律**变成可复跑的默认流程：任何改动都要先定影响面、经用户确认编号清单，再实施，最后用门禁脚本 + 验证脚本产出可推翻的证据。

规则的唯一来源是项目根 `AGENTS.md`（事实源地图、编码约定、红线、DoD）。本 skill 只定**顺序与判据**，不复述裁定标准；开始任何一步前先读 `AGENTS.md`，并按需读 `references/playbooks.md` 的对应行。不要凭对本文件的记忆改代码。

## 先分轨道

| 轨道 | 判定 | 走几步 |
| --- | --- | --- |
| 轻轨 | 纯文档措辞、注释、补单测、脚本输出格式；不动契约字段/状态/SQL/配置默认值 | 阶段 0 → 改 → 阶段 4 的两个 `node` 门禁 + 相关脚本 |
| 标准轨 | 改 Java 行为、mapper SQL、错误码归类、配置项、compose 参数 | 全五阶段 |
| 重轨 | 动冻结契约（OpenAPI / Kafka schema / 状态机 / 迁移 / 权限判定 / 引擎端口语义），或关闭 backlog 阻断项 | 全五阶段 + 契约评审 + 阶段 4 全量复跑 + 新 ADR |

判不准就按更重的轨道走。

## 阶段 0 · 调研（不许跳过）

1. 按 `AGENTS.md` §2 读：目标模块代码 → 对应契约文件 → 相关 ADR / backlog 行 / 报告（引用数字必须带口径）。
2. 读 `outputs/P1落地实现说明.md` §5–§6：已修缺陷与**未验证边界**，避免把目标契约当现状。
3. 跑基线，把存量问题记下来（**不顺手修**，除非用户要求）：
   ```bash
   node .qoder/skills/kb-dev-workflow/scripts/check-evidence.cjs
   node .qoder/skills/kb-dev-workflow/scripts/check-hygiene.cjs
   ```
4. 产出：一句话结论 + 影响面清单（契约/迁移/配置/代码/测试/文档/报告各列出受影响项）+ 明确"不做什么"。

## 阶段 1 · 编号任务清单

用 `references/templates.md` 的清单模板，每项必须写**完成判据**（一条可复跑的证据或一个可核对的文件同步）。
重轨要在清单里点名：动到哪份冻结件、向后兼容还是版本化、需要补哪条负向用例、复测日期。

## 阶段 2 · 确认

把清单交给用户并**明确取得同意**后才动代码。用户说"直接改"可视为授权，但完成后仍要交门禁结果。
禁止：未经确认改契约、删测试降低门槛、把结构校验通过当作行为验证。

## 阶段 3 · 实施

- 按 `references/playbooks.md` 找到对应变更类型，**同一批次内把它要求的每一面都同步完**（契约 ↔ 代码 ↔ 文档三方面分开提交算未完成）。
- 只碰本任务需要的文件；不顺手重构、不为假想需求加抽象。
- 新增错误码/配置项/主题/状态：先进契约再写代码，不要反过来。
- 迁移只追加；改 SQL 用显式 `AS` 驼峰别名（照 `TaskMapper.COLUMNS`）。

## 阶段 4 · 门禁

标准轨与重轨按顺序全跑，任何 ERROR 停下来修，不解释掉、不加例外：

```bash
node .qoder/skills/kb-dev-workflow/scripts/check-evidence.cjs   # 引用与证据链完整
node .qoder/skills/kb-dev-workflow/scripts/check-hygiene.cjs    # 交付物红线（口令/镜像/忽略规则）
cd p1/portal && mvn -o clean test                                # 行为（贴真实测试计数）
python p0/scripts/04-check-contracts.py                          # 契约漂移，退出码非 0 即漂移
bash p0/scripts/11-sync-migrations.sh                            # 只在动了迁移时跑
```

按影响面补跑（`05`/`06`/`07`/`09` 需要门户在 `:18180` 跑着，先 `bash p0/scripts/07-run-app.sh`）：
权限矩阵 `05-verify-authz.py` · 入库链路 `06-verify-ingest.py` · 检索与 ACL `07-verify-retrieval.py` ·
质量基线 `09-run-eval.py` · 断网 `10-verify-offline.py` · 备份恢复 `12-backup-restore.sh` · 故障注入 `13-fault-drill.py`。
`10`/`12`/`13` 会启停容器或断网，**跑前先向用户确认时机**。

`04` 只覆盖 P0 侧事件类，改 P1 的 `Events`/`KbProperties` 字段时它不报警 —— 按 playbook 人工对照 schema 与配置目录。

## 阶段 5 · 证据落地

- 结论写回该写的地方：取舍 → `p0/decisions/ADR-*.md`（必须能被某份报告证实或推翻）；待办 → `P1-backlog.md`（编号登记后不变，带关闭证据/责任人/日期）；实现状态 → `outputs/P1落地实现说明.md`；实测结果 → `p0/evaluation/reports/`。
- 报告只写哈希与计数，不写口令、Prompt 与文档正文。
- 未复跑的部分如实写"未复跑 + 风险"，不写成通过。

## 交付回复格式

```
改了什么：<文件:行 + 一句话>
门禁：<每条命令 + 真实退出码/测试计数（Tests run: N）/剩余 WARN>
契约同步：<动到的冻结件 + 未动的为什么不用动>
证据写回：<ADR/backlog/报告路径>
未做与原因：<如实>
下一步：<一条可复跑的验证>
```

## 硬性规则

1. 清单未经确认不动代码；一次改动只解决一个问题。
2. **结构校验 ≠ 行为测试**；端到端脚本没跑就是没跑。
3. 门禁脚本报 ERROR 只能被修复或被登记成 backlog 项，不许注释绕过；脚本自身有 bug 时先修脚本，并在清单里单列一项。
4. 不改 `p0/` 的验证语义来迁就新代码；P0 是历史证据，需要变更就写新 ADR。
5. 引用任何指标前确认口径（环境、注入的性能参数、语料来源），否则不引用。

## Resources

- `references/playbooks.md`：变更类型 → 必须同步的面 → 门禁 → 证据（改契约/迁移/权限/管道/部署/评测/文档前读对应行）
- `references/templates.md`：编号任务清单、ADR、backlog 行、报告字段、交付回复的现成骨架
- `scripts/check-evidence.cjs`：ADR 索引↔文件↔证据路径、backlog 登记完整性、全仓库路径引用、Kafka 主题登记
- `scripts/check-hygiene.cjs`：真实凭据是否出现在交付物、可疑字面量、镜像 tag+digest、`.gitignore` 覆盖与空转规则
