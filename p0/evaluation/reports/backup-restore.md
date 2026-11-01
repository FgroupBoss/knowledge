# P0 备份与恢复演练

- 时间（UTC）：20261031T181448Z
- 备份落点：`p0/.tmp/backup/20261031T181448Z`（交付物只含本报告，数据留在本机）
- 方式：`mysqldump --single-transaction`（不锁业务写） + `mc mirror` 整桶原件

- 数据库快照写出并通过 gzip 完整性自检：5.6M
- 原件镜像：278 个对象 / 2.5M；库里非 DELETED 文档 278 行

- 备份已导入临时库 `kb_p0_restore`（运行库零写入）

| 表 | 运行库行数 | 恢复库行数 | 一致 |
| --- | --- | --- | --- |
| tenant | 2 | 2 | ✅ |
| app_user | 9 | 9 | ✅ |
| workspace | 25 | 25 | ✅ |
| workspace_member | 37 | 37 | ✅ |
| knowledge_base | 44 | 44 | ✅ |
| document | 278 | 278 | ✅ |
| ingest_task | 278 | 278 | ✅ |
| outbox_event | 278 | 278 | ✅ |
| event_consumption | 278 | 278 | ✅ |
| idempotency_record | 278 | 278 | ✅ |
| audit_event | 1578 | 1578 | ✅ |
| chat_request | 1102 | 1102 | ✅ |
| citation | 5107 | 5107 | ✅ |
| engine_kb | 42 | 42 | ✅ |
| engine_document | 274 | 274 | ✅ |
| engine_chunk | 1409 | 1409 | ✅ |

## 原件与元数据对账（最近 5 份 READY 文档，按内容哈希）

| 文档 | 版本 | 库内 sha256 | 备份对象 | 一致 |
| --- | --- | --- | --- | --- |
| offline.txt | 1 | 4bcc6929963aeec9… | 命中同一内容 | ✅ |
| offline.txt | 1 | 59c0ba48071c5fb8… | 命中同一内容 | ✅ |
| offline.txt | 1 | 71eebf72306cae5b… | 命中同一内容 | ✅ |
| offline.txt | 1 | 0a7372a806a44c5d… | 命中同一内容 | ✅ |
| offline.txt | 1 | 308197a3b6b9e316… | 命中同一内容 | ✅ |

## 结论

- 表级行数差异 **0** 项；原件内容哈希未匹配 **0** 项。
- 临时库已删除；对象 key 未出现在本报告，只用内容哈希对账（key 改名也不影响可还原性）。
- **判定：通过**——备份可恢复，且「原件 + 元数据」足以重建业务库与索引。

## 已知限制（如实记录）

- 本次是同机旁路恢复，不是跨机灾难恢复：主机/磁盘级故障下的 RPO/RTO 实测仍缺（P1）。
- 备份落在工作目录内；生产必须写独立介质并验证不可变保留与加密。
- 引擎索引未单独备份，按设计它是可从原件重建的派生物，重建路径由 13-fault-drill 的删除→重投演练验证。
