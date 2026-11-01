# ADR-002 权限判定以 MySQL 为唯一权威，Redis 只是可重建的加速件

- 状态：已采纳（2026-10-31）
- 背景：本地知识库要在多租户、多空间、多密级下做到"越权读取 0 例"。缓存里做权限判定速度快，
  但缓存失效、脏读或半更新会直接变成安全事故；撤权必须立刻生效。
- 决定：
  1. 权限权威只有 MySQL：`tenant / app_user / workspace_member / knowledge_base / document_acl`，
     角色四级 OWNER(4)/ADMIN(3)/MEMBER(2)/VIEWER(1) 映射到 `kb:read|kb:write|kb:delete|...`，
     密级 `INTERNAL < SECRET < TOP_SECRET` 用 `clearance.atLeast()` 比较，文档级 ACL 为
     `(subject_type, subject_id, grant_type)` 三元组。
  2. Redis 只缓存"已算好的决策"，键带 `kbId+userId`，值里带 `aclVersion`。读取时比较水位：
     水位缺失、值过旧、Redis 报错——一律回源 MySQL（fail-close，绝不因为缓存不可用而放行）。
  3. 变更权限时先递增 DB 版本、抬升 Redis 水位、再精确删除受影响键，不用 `KEYS/SCAN`。
  4. 召回阶段引擎可以超取（top-k 20），但每条候选在生成前都要用 MySQL 逐条复核，
     被丢弃的按 `droppedByAcl / droppedByClearance / droppedByStaleVersion` 计数；
     引用快照落库只写复核后仍可用的片段。
  5. 引用内容只能通过服务端代理 `/citations/{id}/preview` 读取，响应强制 `Cache-Control: no-store`，
     不发任何可被外部长期持有的预签名地址。
- 后果：
  - 正面：权限面只有一处真相，撤权、降密、跨租户路径在负向矩阵里全部可判定；
    Redis 被清空或整机停机不影响正确性（只影响延迟），也不需要 `CACHE_UNAVAILABLE` 这类错误码。
  - 负面：每条候选多一次 MySQL 复核，问答延迟随 top-k 上升；P1 若上真引擎（ADR-003）必须把复核
    改成批量而不是逐条。
  - **缺陷（2026-11-01 故障注入演练实测）**：决策缓存目前是**只写不读**的。`FLUSHALL` 之后
    `hits` 始终为 0（`FD-02` 证据行：misses 上升、hits `0→0`，全部结论来自 MySQL 回源），因为水位键
    `<ns>:aclwm:<kbId>` 只在权限变更 `invalidate()` 时写入，而读路径 `find()` 一见水位缺失就判定 miss 回源、
    且从不把水位补回去——清空后水位永久缺失，缓存条目写得再多也读不到。
    正确性不受影响（fail-close 回源，引用集合与清空前逐字一致，Redis 停机也照样出正确结论），
    但**加速收益为 0**，每轮问答反而多付一次水位读 + 一次决策读两次 Redis 往返。
    修法二选一：回源成功后由读路径建立水位；或去掉水位键，把 `aclVersion` 直接内联进决策值做版本比对。
- 证据：`evaluation/reports/authz-matrix.md`（26/26，越权成功 0 例）、
  `evaluation/reports/retrieval-acl.md`（29/29，含陈旧水位投毒被拒、撤权后引用不可读）、
  `evaluation/reports/fault-drill.md` FD-01~FD-04（FLUSHALL 与 Redis 停机期间问答一律 200、
  引用集合一致、停机被计入 `redisErrors` 而不是静默吞掉；同一组用例同时暴露了上面的只写不读缺陷）。
- 责任人 / 复测：安全 + Java；生产语料与真引擎接入后重跑负向矩阵（2026-11-13 前）。
  缓存读路径修复后必须补一条断言：清空后的第二次问答 `hits` 上升且引用集合不变（P1 backlog #5）。
