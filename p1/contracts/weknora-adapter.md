# WeKnora 引擎适配契约（P1，未冻结：无真机验证）

事实源是 `p1/portal/src/main/java/com/acme/kb/p1/engine/WeKnoraAdapter.java` 与
`WeKnoraCapabilityProbe.java`。本文件把两者的线上行为写成可核对的契约，
改代码必须同步改这里——ADR-003 的口径：**引擎是派生索引的持有者，不是授权判定者**。

## 边界

- 适配器只实现 `KnowledgeEnginePort` 的六个方法（`name/ensureKnowledgeBase/indexDocument/
  offlineDocument/deleteDocument/search`）＋ 一个供探测清理的 `deleteKnowledgeBase`。
  业务代码不得调用 `deleteKnowledgeBase`。
- 不出现引擎内部表字段；不假设引擎有租户/用户模型，只要求它**透传并回传**我们写进去的元数据。
- 授权判定在 Java 侧（MySQL 复核 + ACL 版本键），适配器只负责"召回前范围过滤"与"元数据不全即丢"。

## 端点与载荷

路径可被配置覆盖，便于对固定版本镜像联调；下表是默认值。

| 方法 | 请求 | 期望响应 |
|---|---|---|
| `ensureKnowledgeBase` | `POST {kb-path}`，JSON：`name=kb-{kbId}`、`external_id=kbId`、`metadata{tenant_id,workspace_id,kb_id,classification,acl_version}` | `id` 或 `knowledge_base_id`，二者皆空 ⇒ `ENGINE_UNAVAILABLE` |
| `indexDocument` | `POST {kb-path}/{engineKbId}/documents`，`multipart/form-data`：`tenant_id,workspace_id,kb_id,document_id,document_version,classification,acl_version,operation_id,display_name,sha256` + `file`（带 filename 与 MIME） | `document_id` 或 `id`（皆空 ⇒ `ENGINE_INDEX_FAILED`）；`chunk_count`/`chunks` 取分片数；`parser`/`chunker`/`embedding_model` 缺省填 `weknora` |
| `offlineDocument` | `POST {kb-path}/documents/{engineDocId}/offline`，JSON `{"searchable": false}` | 仅记录日志，非 2xx 抛 `ENGINE_UNAVAILABLE` |
| `deleteDocument` | `DELETE {kb-path}/{engineDocId}` | 2xx 或 **404 视为已删**；其余非 2xx 返回 `false`，由调用方重试/进死信 |
| `search` | `POST {search-path}`，JSON：`knowledge_base_ids[]`、`query`、`top_k`、`filters.tenant_id` | `items[]`（回退读 `data[]`）|
| `health` | `GET {base-url}/health`，5s 超时 | 2xx ⇒ up，其余/unreachable ⇒ down（不抛错） |

响应外层若是 `{"data": {...}}` 且不含 `items`，会先解包 `data` 再取字段。

## 召回结果的 fail-close 规则

`search` 对每条候选按 `metadata` 优先、顶层回退取值，别名对 `{snake_case, camelCase}` 兼容。
以下任一成立即**丢弃该条并 WARN 留痕**，绝不进入 Prompt：

1. `tenant_id / workspace_id / kb_id / document_id / engine_kb_id` 任一缺失 → `reason=missing_metadata`；
2. `tenant_id` 与请求租户不符，或 `engine_kb_id` 不在请求的 `knowledge_base_ids` 集合内
   → `reason=out_of_scope`。

`engineKbIds` 为空时直接返回空列表，不发请求。逐条回 MySQL 的复核在 `ChatService` 再做一次，
适配器这一层只是"不让明显越界的东西进模型上下文"。

## 错误映射

| 情况 | 错误码 | 语义 |
|---|---|---|
| `HttpTimeoutException` / 线程中断 | `ENGINE_TIMEOUT` | 暂时性，可退避重试 |
| `IOException`（连接失败） | `ENGINE_UNAVAILABLE` | 暂时性 |
| 非 2xx 状态码 | 按调用点传入的 failure 码（`ENGINE_UNAVAILABLE` / `ENGINE_INDEX_FAILED`） | 见 `error-codes.md` 二分表 |
| 响应体不可解析 | 同调用点 failure 码 | 永久/暂时由上层判定 |
| 序列化失败 | `INTERNAL_ERROR` | 我方缺陷，不重试 |

## 能力探测门禁（切换 provider 的前置条件）

`kb.engine.capability-probe=true` 时在启动阶段执行：建临时库 → 索引带 marker 的探测文档
（等待召回最多 60s，每 2s 轮询）→ **越界 tenant 检索必须 0 条** → 正确 tenant 检索必须命中且
结果带 `document_id` → 清理临时文档与库。任一步失败抛 `ENGINE_ACL_UNSUPPORTED` 并拒绝启动。

未在该目标镜像上跑通探测，**不得对外宣称"已接入 WeKnora"**（当前仓库环境没有 WeKnora 实例，
本契约与适配器均属"可联调"状态，行为未经验证）。

## 配置项

| 环境变量 | Spring 属性 | 默认值 |
|---|---|---|
| `KB_ENGINE_PROVIDER` | `kb.engine.provider` | `stub`（试点切 `weknora` 前须过探测） |
| `KB_ENGINE_BASE_URL` | `kb.engine.base-url` | 空则启动失败（`stub` 形态有本地默认值） |
| `KB_ENGINE_TIMEOUT_MS` | `kb.engine.timeout-ms` | `60000` |
| `KB_ENGINE_TOKEN` / `KB_ENGINE_TOKEN_FILE` | `kb.engine.token` / `token-file` | 无默认；有值时加 `Authorization: Bearer` |
| `KB_WEKNORA_KB_PATH` | `kb.engine.weknora-kb-path` | `/api/v1/knowledge-bases` |
| `KB_WEKNORA_SEARCH_PATH` | `kb.engine.weknora-search-path` | `/api/v1/search` |
| `KB_ENGINE_CAPABILITY_PROBE` | `kb.engine.capability-probe` | `true` |

口令与 token 只走 `*_FILE`，不进文档、日志与报告。
注意：以上键**尚未录入** `p0/contracts/config-catalog.md`（该表冻结在 P0 的 `P0_*` 命名域），
补录见 `P1-backlog.md` 第 22 项。

## 同步面

改这个适配器时，需要同时检查：本文件、`p0/contracts/error-codes.md`（错误码二分）、
`p0/contracts/config-catalog.md`（配置项）、`KnowledgeEnginePort`（端口签名）、
`StubKnowledgeEngine`（测试替身是否漂移）、`outputs/P1落地实现说明.md` 的"未验证边界"一节。
