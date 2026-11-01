# ADR-010 Wiki 正文的权威源是 MySQL，引擎索引与对象存储都不是真相

- 状态：已采纳（W1 侧证据），**备份路径未复跑**（2026-11-01）
- 背景：P2 要把"人写的事实"（Wiki 页面）与"机器算出来的召回结构"（引擎索引）分开。
  D-01/D-03 的取舍点是：正文放 MySQL 还是像文档一样放 MinIO + 元数据表。文档走对象存储是因为
  原件不可变且体积大；Wiki 正文是可变的纯文本，每次编辑都产生新修订，用对象存会造出
  "修订历史与对象键两套真相"。
- 决定：
  1. `wiki_revision.body`（`MEDIUMTEXT`）与 `body_digest` 是正文的唯一权威；引擎侧 `WIKI_PAGE`
     索引只是派生物，随时可按 `pageId + revisionNo` 重建。
  2. 参与审计哈希链的时间先 `truncatedTo(MILLIS)`；`metadata_json` 沿用 ADR-005 的结论用
     `MEDIUMTEXT` 不用 JSON 列（MySQL 会补空格/重排，读回字节 ≠ 写入字节 → 链校验必错）。
  3. 治理判定永远回 MySQL：页面 `status`、`published_revision_id`、ACL、来源可得性
     都不接受"引擎说有就有"（ADR-002 的同一原则）。
  4. 正文不进审计元数据、不进日志、不进报告；审计只写计数与摘要哈希（红线 1）。
- 后果：
  - 正面：修订历史、差异计算、冲突提示都在同一个事务里可读可写；备份口径简化为 mysqldump 一份。
  - 负面：Wiki 备份完全依赖数据库备份，对象存储的副本策略帮不上忙；
    **恢复后页面可读、修订完整、索引重建成功这三件事本轮未验证**（W4-03，未复跑）。
    200 KiB 级正文放在 `MEDIUMTEXT` 里，差异计算的行级预算必须显式（见 ADR-013）。
- 证据：`p1/evaluation/reports/p2-wiki-contracts-and-w1.md` PW-02（`wiki_revision` 列与
  `uk_wiki_rev_page_no` 就位）、PW-14（审计元数据里正文计数为 0、链自洽）、
  PW-13（`WikiDiffTest` 的体积预算行为）；复跑 `cd p1/portal && mvn -o clean test`。
- 责任人 / 复测：Java；W4-03 备份恢复复跑时把「恢复后修订完整」补进同一份报告（2026-12-04 前）。
