# 入库链路（上传 → outbox → Kafka → 引擎索引 → READY）验证

- 生成时间：2026-11-01 01:20:08
- 目标服务：http://127.0.0.1:18180
- 结论：**19/19 条通过**

| 用例 | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| UP-01 | 合法上传返回 202 与任务句柄 | PASS | 202 status=UPLOADED |
| UP-02 | 上传后在 180s 内经 outbox→Kafka→引擎收敛为 READY | PASS | elapsed=2055ms status=READY chunks=8 |
| UP-03 | 缺少 Idempotency-Key 必须 400 | PASS | 400/VALIDATION_FAILED |
| UP-04 | Idempotency-Key 非 UUID 必须 400 | PASS | 400/VALIDATION_FAILED |
| UP-05 | 空文件必须 400 | PASS | 400/VALIDATION_FAILED |
| UP-06 | 扩展名不在白名单必须 422 DOCUMENT_FORMAT_UNSUPPORTED | PASS | 422/DOCUMENT_FORMAT_UNSUPPORTED |
| UP-07 | 扩展名与内容签名不符必须 422 DOCUMENT_SIGNATURE_MISMATCH | PASS | 422/DOCUMENT_SIGNATURE_MISMATCH |
| ID-01 | 同一主体同一 Idempotency-Key 重发返回同一文档且不新建任务 | PASS | 202/202 same=True duplicate=True |
| ID-02 | 重发不产生第二次有效索引 | PASS | engine_document rows=1 |
| VR-01 | 同内容不同 Key 生成新版本并收敛（版本递增而非静默复用） | PASS | version=3 status=READY elapsed=2039ms |
| IX-01 | 任一文档在引擎侧只有一份有效索引行（无重复有效索引） | PASS | duplicated document_id groups=0 |
| IX-02 | 引擎侧不存在孤儿索引行 | PASS | orphan engine rows=0 |
| BK-01 | 收敛后无未发布事件、无死信、无未收敛文档 | PASS | outbox=0 dlq=0 unconverged=0 |
| BK-02 | 入库组 lag 可读且收敛后为 0（组名与监听端一致，不是查错组的假零） | PASS | group=p0-ingest lag=0 current=9 end=9 |
| BK-03 | 删除组单独计量，不与入库组混成一个数字 | PASS | delete group=p0-delete lag=0 |
| TX-01 | 事务性 outbox 一次即发布成功（无重试放大） | PASS | max outbox attempts=1 |
| TX-02 | 消费账本有记录，说明重复投递可被识别 | PASS | event_consumption rows=9 succeeded tasks=9 retried tasks=0 |
| LIM-01 | 超过上限（104857600 字节）必须 413 DOCUMENT_TOO_LARGE | PASS | 413/DOCUMENT_TOO_LARGE |
| ST-01 | 每个文档都登记了唯一对象键（原件与行的对应关系可审计） | PASS | null keys=0 dup keys=0 docs=9 |

## 取证与说明
- 首文档收敛耗时 2055 ms（含 Kafka 轮询间隔与 Ollama 向量调用）
- backlog={'outboxUnpublished': 0, 'outboxOldestAgeSeconds': 0, 'tasksByStatus': {'SUCCEEDED': 9}, 'kafka': {'consumerGroup': 'p0-ingest', 'partitions': 6, 'currentOffset': 9, 'endOffset': 9, 'lag': 0}, 'deleteKafka': {'consumerGroup': 'p0-delete', 'partitions': 3, 'currentOffset': 0, 'endOffset': 0, 'lag': 0}, 'dlqCount': 0, 'unconvergedDocuments': 0, 'engineChunks': 68, 'searchableEngineDocs': 9, 'cacheSt

上传上限 104857600 字节由 P0_UPLOAD_MAX_BYTES 同时驱动 servlet 与业务校验，两者不会错位。
