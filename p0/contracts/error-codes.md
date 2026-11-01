# P0 错误码契约（冻结）

`ErrorCode` 枚举、`contracts/openapi.yaml` 的 `ApiError.code` 与本文一一对应，
由 `scripts/04-check-contracts.py` 机器校验：**枚举里声明了但代码发不出来的码，与代码发得出来
却没进枚举的码，都算契约漂移**。P0 因此删除了两个从不产生的码（见文末"已删除的码"）。

## 响应结构

```json
{ "code": "ACCESS_DENIED", "message": "无权访问该知识库",
  "requestId": "01J8...", "traceId": "01J8...",
  "details": [ { "field": "kbId", "reason": "..." } ] }
```

- `message` 面向用户，**绝不含**对象键、Prompt、SQL、堆栈、凭据、对方租户标识。
- `traceId`/`requestId` 同时进日志 MDC 与 `audit_event`，运维据此定位堆栈（堆栈只在日志里）。
- `details` 仅参数校验类错误填充，其余为 `null`（Jackson `non_null`）。
- 未预期异常统一 `INTERNAL_ERROR` + HTTP 500，返回体不含异常类名。

## 码表

| code | HTTP | 触发点（类:场景） | 分类 | 客户端动作 |
|---|---|---|---|---|
| `VALIDATION_FAILED` | 400 | `ApiAdvice` 参数校验/请求体不可读/缺 `Idempotency-Key`；`UploadValidator`；`FixtureService` 标识与角色取值 | 永久 | 修正请求，不换键重发 |
| `UNAUTHENTICATED` | 401 | `IdentityService`（身份未映射/停用/租户不可用/身份域不符）、`ActorResolverFilter`、`ActorHolder` | — | 重新登录，取新断言 |
| `ACCESS_DENIED` | 403 | `AuthorizationService`（角色位不足、文档级 ACL 未授权）、`FixtureGuard`（夹具未启用/口令不符）、`FixtureController`（取证端点需 OWNER/ADMIN） | 永久 | 不重试；需要权限则走授权变更流程 |
| `CLEARANCE_TOO_LOW` | 403 | `AuthorizationService`（知识库与文档密级高于主体许可） | — | 不重试；密级不可自助提升 |
| `CROSS_TENANT_ACCESS` | — | **只写审计 `reason_code`，不作为响应返回**（跨租户命中对方 kbId/documentId 时响应降级为 404） | 永久 | 无从感知；按 404 处理 |
| `RESOURCE_NOT_FOUND` | 404 | `ApiException.notFound`、`ObjectStorageService`（对象不存在）、`DocumentService`（任务不存在/跨租户降级） | — | 校验 ID 与归属 |
| `IDEMPOTENCY_CONFLICT` | 409 | `DocumentService`（同 `Idempotency-Key` 携带不同摘要；并发同内容撞 `uk_doc_kb_hash_version`）、`ApiAdvice` 兜底 `DataIntegrityViolationException` | — | 用新键重发，或查询既有文档 |
| `INVALID_STATE_TRANSITION` | 409 | `DocumentWriteTx`（状态已变化/当前状态不允许删除）、`DocumentService.delete`、`CitationService`（引用版本已过期，同时写审计） | — | 重新 GET 取最新状态后再操作 |
| `TASK_NOT_RETRYABLE` | 409 | `DocumentService.retry`（非 FAILED；或上次错误为永久性） | — | 永久错误须重新上传文件 |
| `DOCUMENT_FORMAT_UNSUPPORTED` | 422 | `UploadValidator`（扩展名不在清单）、引擎判定不可解析 | 永久 | 换格式或修文件 |
| `DOCUMENT_SIGNATURE_MISMATCH` | 422 | `UploadValidator`（MIME 与扩展名不符、魔数不符、文件过小）、`IngestProcessor`（**原件 SHA-256 与登记值不符，拒绝索引**） | 永久 | 重新上传正确原件 |
| `DOCUMENT_TOO_LARGE` | 413 | `UploadValidator`（超 `p0.upload.max-bytes`）、`ApiAdvice` 的 `MaxUploadSizeExceededException` | 永久 | 拆分或压缩后重传 |
| `CHAT_NO_EVIDENCE` | 422 | `ChatService`：授权后无候选 chunk，或模型未产出可引用正文 → **拒答**（`refusal=NO_EVIDENCE`），绝不猜答案 | — | 缩小问题或补文档 |
| `ENGINE_UNAVAILABLE` | 503 | `PendingWeKnoraAdapter`（引擎未接入时显式失败，不伪装成空结果）、引擎健康探测失败 | 暂时 | 由管道退避重试 |
| `ENGINE_TIMEOUT` | 503 | `OllamaChatModelClient`/`OllamaEmbeddingProvider`（超时或不可达、请求被中断） | 暂时 | 同上 |
| `ENGINE_INDEX_FAILED` | 502 | `IngestProcessor.delete`（引擎未能确认索引已删除） | 暂时 | 入库重试；删除则进补偿流程 |
| `STORAGE_UNAVAILABLE` | 503 | `ObjectStorageService` 读写删失败 | 暂时 | 入库重试；删除置 `COMPENSATION_REQUIRED` |
| `MODEL_UNAVAILABLE` | 503 | Ollama 返回非 2xx、响应结构不符、未产出正文 | 暂时 | 重试；离线场景需检查模型权重是否在位 |
| `RATE_LIMITED` | 429 | `ChatRateLimiter`（每主体每分钟 `p0.chat.rate-per-minute`） | — | 读 `Retry-After` 语义后降速 |
| `CONCURRENCY_LIMITED` | 429 | `UploadValidator`（并发上传槽位满）、Ollama 并发信号量满 | 暂时 | 退避后重试 |
| `INTERNAL_ERROR` | 500 | `ApiAdvice` 兜底、SSE 流内异常、`FixtureService` 租户上下文缺失 | — | 携 `traceId` 报障，不自动重试写操作 |

## 与重试/DLQ 的关系

- `permanent()`：`DOCUMENT_FORMAT_UNSUPPORTED`、`DOCUMENT_SIGNATURE_MISMATCH`、`VALIDATION_FAILED`、
  `CROSS_TENANT_ACCESS`、`ACCESS_DENIED` → 任务直接 `FAILED`，**不发重试事件**。
- `transientFailure()`：`ENGINE_TIMEOUT`、`ENGINE_UNAVAILABLE`、`ENGINE_INDEX_FAILED`、
  `STORAGE_UNAVAILABLE`、`MODEL_UNAVAILABLE`、`CONCURRENCY_LIMITED` → 发 `DOCUMENT_INGEST_RETRY`
  （`retryReason` 归类见 `Events.retryReason()`），达 `max_attempts` 后置 `DEAD_LETTERED` 并写
  `INGEST_DEAD_LETTERED` 信封到 `kb.ingest.dlq.v1`。
- Redis 任何异常都不映射为错误码：授权缓存是优化层，失败即回源 MySQL（fail-close），
  客户端与任务状态都感知不到缓存抖动。

## 已删除的码（P0 冻结时移除）

| 码 | 删除原因 |
|---|---|
| `DUPLICATE_UPLOAD` | 同库重复上传按"新版本"处理，唯一键冲突走 `IDEMPOTENCY_CONFLICT`，没有任何路径能发出它 |
| `CACHE_UNAVAILABLE` | 缓存降级被设计成对客户端不可见；声明一个永不返回的码会让 P1 客户端为死分支写代码 |
