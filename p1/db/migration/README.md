# P1/P2 迁移评审副本

这里是 **副本**，不是执行源。执行源只有：

```
p1/portal/src/main/resources/db/migration/     ← Flyway 真正跑的
```

| 项 | 值 |
|---|---|
| 覆盖范围 | V1–V11（P1）+ V12–V18（P2 Wiki 治理） |
| 生成方式 | `bash p1/scripts/30-sync-migrations.sh`（单向复制，绝不反向写回） |
| 核对方式 | 逐文件 SHA-256 比对；副本多出文件即非零退出 |
| 判定实现 | 与 `p0/scripts/11-sync-migrations.sh` 同一份脚本，只换 `SRC_DIR` / `DST_DIR` |
| 用途 | DBA / 契约评审：不翻源码目录就能看到全部 DDL 演进 |

## 规则

1. **只追加**：历史版本一旦落地不再修改（Flyway `out-of-order=false`、`baseline-on-migrate=false`）。
   要改已发布的表结构，加一个新版本号。
2. 这里的文件**不参与构建**：门户的 Flyway location 是 `classpath:db/migration`，
   与本目录无关，不存在重复执行或"评审副本被误当执行源"的路径。
3. 改完执行源必须复跑 30 号脚本并把逐文件哈希记进评审结论；
   哈希不一致时脚本非零退出，不接受"看起来一样"。
4. 建表纪律（ADR-005）：主键 `CHAR(36)`、无外键、时间列 `DATETIME(3)` 一律 UTC、
   `metadata_json` 用 `MEDIUMTEXT` 不用 `JSON` 列。
5. 运行账号 `kb_p1_app` 只有 SELECT/INSERT/UPDATE/DELETE，DDL 走 `kb_p1_ddl`；
   新增表后要在 `p1/compose/README.md` 的授权 SQL 里同步。

## 2026-11-01 首次纳管记录

`bash p1/scripts/30-sync-migrations.sh` → 更新 11 个文件、退出码 0；
再跑一次 → 更新 0 个文件、退出码 0（幂等）；
临时放入 `V90__stray.sql` → 退出码 1 并报「副本中有多出的迁移」。
