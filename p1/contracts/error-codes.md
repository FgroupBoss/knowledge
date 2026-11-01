# P1/P2 错误码契约

本文件是 **pilot 侧（`com.acme.kb.p1`）错误码的唯一事实源**。P0 的 `p0/contracts/error-codes.md`
保留为历史证据不再扩写（裁定见 `p0/decisions/ADR-010-P2契约事实源迁到p1-contracts.md`）。

三方一致由 `p1/scripts/24-check-contracts-p1.py` 机器校验，任一方漂移即非零退出：

1. Java 枚举 `com.acme.kb.p1.common.ErrorCode`
2. 本文件码表（`## 码表` 小节表格第一列）
3. `p1/contracts/openapi-1.2.yaml` 的 `components/schemas/ApiError/properties/code/enum`
4. **声明必须可产生**：码表里每一行都必须在 `p1/portal/src/main/java`（`ErrorCode.java` 除外）里出现
   一次以上（`ErrorCode.X` 或字符串 `"X"`），出现 0 次即 ERROR
5. 列语义核对：`HTTP` 列的三位数字必须等于 `ErrorCode.status()`；`分类` 列必须是 `永久`/`暂时`/`—`，
   且与 `permanent()` / `transientFailure()` 一致（不属于两者才写 `—`）

## 响应结构

沿用 P0：`{code, message, requestId, traceId, details}`。

- `message` 面向用户，**绝不含**对象键、Prompt、SQL、堆栈、凭据、对方租户标识；堆栈只进日志（MDC 带 `traceId`、`actor`）。
- 跨租户命中一律降级为 404 + `RESOURCE_NOT_FOUND`；`CROSS_TENANT_ACCESS` **只写审计 `reason_code`**，不作响应。
- 未预期异常统一 `INTERNAL_ERROR` + 500，返回体不含异常类名。

## 码表

「产生点」列写类名与方法名，可 `grep` 核对；未接线的码不进本表（见文末「待接线的码」）。

| code | HTTP | 产生点 | 分类 | 首次出现阶段 | 客户端动作 |
|---|---|---|---|---|---|
| `VALIDATION_FAILED` | 400 | `ApiAdvice.onBadRequest/onInvalid`、`UploadValidator`、`CorrectionCase`（W3：`RESOLVED` 缺关联修订） | 永久 | P0 | 修正请求，不换键重发 |
| `UNAUTHENTICATED` | 401 | `ActorResolverFilter`、`ActorHolder.require`、`IdentityService` | — | P0 | 重新登录 |
| `ACCESS_DENIED` | 403 | `AuthorizationService`（角色位不足、文档级 ACL）、`WikiCapabilities`/`WikiService`（无 Wiki capability）、`WikiSourceGuard`（来源不可见） | 永久 | P0 | 走授权变更流程，不重试 |
| `CLEARANCE_TOO_LOW` | 403 | `AuthorizationService`（KB/文档密级高于主体许可） | — | P0 | 密级不可自助提升 |
| `CROSS_TENANT_ACCESS` | 403 | `AuthorizationService.deny(...)`、`WikiService` 跨租户分支：**只写审计 `reason_code`**，HTTP 响应降级为 404 `RESOURCE_NOT_FOUND`，本码不作响应体 | 永久 | P0 | 无从感知 |
| `RESOURCE_NOT_FOUND` | 404 | `ApiException.notFound`、`ObjectStorageService`、`DocumentService`、`WikiService`（跨租户/不存在统一） | — | P0 | 校验 ID 与归属 |
| `IDEMPOTENCY_CONFLICT` | 409 | `DocumentService.upload`（同键不同摘要）、`ApiAdvice.onConflict` 兜底唯一键冲突 | — | P0 | 用新键重发或查既有资源 |
| `INVALID_STATE_TRANSITION` | 409 | `DocumentWriteTx`（状态已变化）、`WikiStateMachine.guard`/`WikiWriteTx`（`UPDATE ... WHERE status=前置态` 影响 0 行） | — | P0 | 重新 GET 取最新状态 |
| `TASK_NOT_RETRYABLE` | 409 | `DocumentService.retry`（非 FAILED，或上次为永久性错误） | — | P0 | 永久错误须重新上传 |
| `DOCUMENT_FORMAT_UNSUPPORTED` | 422 | `UploadValidator`（扩展名不在清单） | 永久 | P0 | 换格式 |
| `DOCUMENT_SIGNATURE_MISMATCH` | 422 | `UploadValidator`（MIME/魔数不符）、`IngestProcessor`（原件摘要不符） | 永久 | P0 | 重新上传正确原件 |
| `DOCUMENT_TOO_LARGE` | 413 | `UploadValidator`、`ApiAdvice.onTooLarge` | — | P0 | 拆分或压缩后重传 |
| `CHAT_NO_EVIDENCE` | 422 | `ChatService`：授权后无候选或模型未产出可引用正文 → 拒答 | — | P0 | 缩小问题或补文档 |
| `ENGINE_UNAVAILABLE` | 503 | `WeKnoraAdapter`/`StubKnowledgeEngine` 健康探测失败、引擎显式不可用 | 暂时 | P0 | 管道退避重试 |
| `ENGINE_TIMEOUT` | 503 | `OllamaChatModelClient`/`OllamaEmbeddingProvider`/引擎调用超时 | 暂时 | P0 | 退避重试 |
| `ENGINE_INDEX_FAILED` | 502 | `IngestProcessor.delete/index` 未获引擎确认 | 暂时 | P0 | 入库重试；删除进补偿 |
| `STORAGE_UNAVAILABLE` | 503 | `ObjectStorageService` 读写删失败 | 暂时 | P0 | 重试；删除置 `COMPENSATION_REQUIRED` |
| `MODEL_UNAVAILABLE` | 503 | Ollama 非 2xx、结构不符、未产出正文 | 暂时 | P0 | 重试；离线检查模型 |
| `RATE_LIMITED` | 429 | `ChatRateLimiter`（每主体/每租户每分钟窗口） | — | P0 | 读 `Retry-After` 后降速 |
| `CONCURRENCY_LIMITED` | 429 | `UploadValidator` 并发槽满、模型信号量满 | 暂时 | P0 | 退避后重试 |
| `INTERNAL_ERROR` | 500 | `ApiAdvice.onUnexpected`、SSE 流内异常 | — | P0 | 携 `traceId` 报障，不自动重试写 |
| `CSRF_INVALID` | 403 | `SecurityConfig` 的 `accessDeniedHandler`：`CsrfException`（token 缺失/不符，`CookieCsrfTokenRepository`，仅非 dev 模式）→ `CSRF_INVALID`，其余拒绝仍为 `ACCESS_DENIED` | — | P1 附录 C（历史欠账，本次登记） | 重新取 token 后重试 |
| `AUDIT_WRITE_FAILED` | 503 | `AuditService.recordInTx/recordIndependent` 捕获写失败后抛出（业务整体回滚或拒绝返回内容） | — | P1 附录 C（历史欠账，本次登记） | 稍后重试；携 `traceId` 报障 |
| `VERSION_CONFLICT` | 409 | `WikiWriteTx.governance`：`If-Match` 与 `wiki_page.acl_version` 不符。附录 C 原写的「P1 成员/会话乐观锁」门户尚未实现，不作为产生点 | — | P1 附录 C（历史欠账，本次登记） | 刷新或比较差异，保留本地稿 |
| `CONVERSATION_NOT_FOUND` | 404 | `ConversationService`（非所有者或不存在，统一 404） | — | P1 附录 C（历史欠账，本次登记） | 回会话列表 |
| `WIKI_SOURCE_UNAVAILABLE` | 422 | `WikiSourceGuard.requireSubmittable`：来源文档非 `READY`、版本不存在 | 永久 | P2 | 重新关联有效来源 |
| `WIKI_REVIEW_SELF_APPROVAL` | 403 | `WikiWriteTx.approve`：`revision.author_user_id == actor` | 永久 | P2 | 换审核人 |
| `WIKI_REVIEWER_NOT_ASSIGNED` | 403 | `WikiCapabilities.requireReview`：actor 既非 `OWNER/ADMIN` 也不在 `wiki_review_assignment` | 永久 | P2 | 走指派流程 |
| `WIKI_REVISION_LOST` | 409 | `WikiWriteTx.saveDraft`：`UPDATE wiki_page ... WHERE current_revision_no = :ifMatch` 影响 0 行 | — | P2 | 刷新或比较差异，保留本地稿 |
| `WIKI_TITLE_INVALID` | 400 | `WikiValidator.requireTitle`：标题空/超 200 字/含控制字符 | 永久 | P2 | 修正后重发 |
| `WIKI_BODY_TOO_LARGE` | 413 | `WikiValidator.requireBody`：正文超 `kb.wiki.max-body-bytes` | 永久 | P2 | 拆分页面 |

## 与重试/DLQ 的关系

- `permanent()`：`DOCUMENT_FORMAT_UNSUPPORTED`、`DOCUMENT_SIGNATURE_MISMATCH`、`VALIDATION_FAILED`、
  `CROSS_TENANT_ACCESS`、`ACCESS_DENIED`、`WIKI_SOURCE_UNAVAILABLE`、`WIKI_REVIEW_SELF_APPROVAL`、
  `WIKI_REVIEWER_NOT_ASSIGNED`、`WIKI_TITLE_INVALID`、`WIKI_BODY_TOO_LARGE`
  → 任务直接 `FAILED`，**不发重试事件**、不进 `kb.wiki.*` 重试。
- `transientFailure()`：`ENGINE_TIMEOUT`、`ENGINE_UNAVAILABLE`、`ENGINE_INDEX_FAILED`、
  `STORAGE_UNAVAILABLE`、`MODEL_UNAVAILABLE`、`CONCURRENCY_LIMITED` → 按
  `backoff_base_ms × 2^attempts` 进退避，超 `max_attempts` 进 DLQ。
- Redis 异常不映射错误码：缓存是加速件，异常即回源 MySQL（fail-close），客户端感知不到抖动。

## 待接线的码（**当前不声明**，声明必须可产生）

| 拟用码 | 拟触发点 | 接线阶段 | 现在为什么不进码表 |
|---|---|---|---|
| `WIKI_SYNC_COMPENSATION_REQUIRED` | `WikiRetractConsumer`（撤稿链路失败并已置补偿态） | P2-W2（依赖准入 F-1 真引擎） | 门户里没有 `WikiRetractConsumer`，没有任何路径能发出它；按 §5「声明必须可产生」不进枚举、码表与 OpenAPI |
| `ENGINE_ACL_UNSUPPORTED` | `WeKnoraCapabilityProbe` | 已实现但仅用于启动 fail-close，不作为 API 响应 | 与 P1 附录 C 口径一致：启动期错误不出现在 API 码表 |

登记在 `p0/decisions/P1-backlog.md`（Wiki 契约补齐项），接线时同步本表与 OpenAPI。
