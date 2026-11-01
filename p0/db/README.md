# 数据库迁移交付副本

这里的 SQL 是 **门户实际执行的那一份** 的镜像，事实源在
`portal-poc/src/main/resources/db/migration/`（Flyway 从 classpath 加载，启动即校验版本号与校验和）。
本目录存在的目的只有两个：交付包里必须有一份可直接评审的 DDL；DBA 在生产执行前需要能脱离应用读到建表语句。

同步方式：

```bash
bash p0/scripts/11-sync-migrations.sh
```

脚本单向复制、逐文件比对 SHA-256，并在副本目录出现源目录没有的迁移文件时直接失败——
不允许"评审用的 DDL"和"实际跑起来的 DDL"各自演化。

## 迁移序列

| 版本 | 文件 | 内容 |
| --- | --- | --- |
| V1 | `V1__p0_identity_and_scope.sql` | 租户、用户（身份主键 `(issuer, subject)`）、工作空间与成员、知识库、文档级 ACL |
| V2 | `V2__p0_document_pipeline.sql` | 文档权威行、入库任务（租约/重试/死信）、事务性 Outbox、幂等记录、事件消费台账、审计事件 |
| V3 | `V3__p0_chat_and_engine.sql` | 问答请求、引用落库、引擎侧文档与分片镜像（含 `acl_version` 快照） |

约定（见 ADR-005）：主键一律 `CHAR(36)` 存 UUID 规范字符串；时间列 `DATETIME(3)` 一律 UTC；
业务库与引擎镜像分表存放，引擎侧字段只作召回与取证用，权威判定永远回到 MySQL。
P0 期间 DDL 由应用账号执行（Flyway），生产环境的账号拆分（DDL 账号 ≠ DML 账号）已列入 P1 backlog。
