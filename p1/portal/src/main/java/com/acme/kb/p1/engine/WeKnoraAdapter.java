package com.acme.kb.p1.engine;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * WeKnora 适配器（provider=weknora）。
 *
 * <p><b>边界声明：</b>本类只实现 {@link KnowledgeEnginePort} 的六个方法，不出现任何引擎内部表字段。
 * 端点与载荷遵循 {@code p1/contracts/weknora-adapter.md} 定义的适配契约；路径可由
 * {@code kb.engine.weknora.kb-path} / {@code search-path} 覆盖，以便对固定版本镜像做联调。</p>
 *
 * <p><b>安全前提：</b>检索请求必须携带 tenant 过滤条件，且返回的每条候选都必须带
 * tenant/workspace/kb/document/version/classification 元数据；缺字段的候选一律丢弃（fail-close），
 * 由 Java 侧再逐条回 MySQL 复核。适配器自身不承担授权判断。</p>
 *
 * <p><b>未验证声明：</b>当前仓库环境没有 WeKnora 镜像/实例，本适配器只做到“可联调 + 能力探测门禁”。
 * 只有 {@link WeKnoraCapabilityProbe} 在目标镜像上通过，才允许在试点环境把 provider 切到 weknora；
 * 未跑过探测就对外宣称“已接入 WeKnora”属于违反 P1 证据纪律。</p>
 */
public class WeKnoraAdapter implements KnowledgeEnginePort {

    private static final Logger log = LoggerFactory.getLogger(WeKnoraAdapter.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String kbPath;
    private final String searchPath;
    private final String token;
    private final Duration timeout;

    public WeKnoraAdapter(HttpClient http, ObjectMapper mapper, KbProperties.Engine config) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = stripTrailingSlash(config.baseUrl());
        this.kbPath = normalizePath(config.weknoraKbPath());
        this.searchPath = normalizePath(config.weknoraSearchPath());
        this.token = resolveToken(config);
        this.timeout = Duration.ofMillis(config.timeoutMs());
    }

    @Override
    public String name() {
        return "weknora";
    }

    @Override
    public String ensureKnowledgeBase(EngineKbSpec spec) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", "kb-" + spec.kbId());
        body.put("external_id", spec.kbId());
        ObjectNode meta = body.putObject("metadata");
        meta.put("tenant_id", spec.tenantId());
        meta.put("workspace_id", spec.workspaceId());
        meta.put("kb_id", spec.kbId());
        meta.put("classification", spec.classification());
        meta.put("acl_version", spec.aclVersion());
        JsonNode response = send("POST", baseUrl + kbPath, json(body), ErrorCode.ENGINE_UNAVAILABLE);
        String id = text(response, "id");
        if (id == null || id.isBlank()) {
            id = text(response, "knowledge_base_id");
        }
        if (id == null || id.isBlank()) {
            throw new ApiException(ErrorCode.ENGINE_UNAVAILABLE, "引擎未返回知识库 ID");
        }
        return id;
    }

    @Override
    public IndexResult indexDocument(IndexRequest request) {
        long begin = System.nanoTime();
        String boundary = "----kb" + UUID.randomUUID();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeField(buf, boundary, "tenant_id", request.tenantId());
        writeField(buf, boundary, "workspace_id", request.workspaceId());
        writeField(buf, boundary, "kb_id", request.kbId());
        writeField(buf, boundary, "document_id", request.documentId());
        writeField(buf, boundary, "document_version", String.valueOf(request.documentVersion()));
        writeField(buf, boundary, "classification", request.classification());
        writeField(buf, boundary, "acl_version", String.valueOf(request.aclVersion()));
        writeField(buf, boundary, "operation_id", request.operationId());
        writeField(buf, boundary, "display_name", request.displayName());
        writeField(buf, boundary, "sha256", request.sha256());
        writeFile(buf, boundary, "file", request.displayName(),
                request.mimeType() == null ? "application/octet-stream" : request.mimeType(),
                request.original());
        writeEnd(buf, boundary);

        HttpRequest httpRequest = authorized(HttpRequest.newBuilder(
                        URI.create(baseUrl + kbPath + "/" + request.engineKbId() + "/documents"))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary))
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build();
        JsonNode response = send(httpRequest, ErrorCode.ENGINE_INDEX_FAILED);
        String engineDocId = text(response, "document_id");
        if (engineDocId == null || engineDocId.isBlank()) {
            engineDocId = text(response, "id");
        }
        if (engineDocId == null || engineDocId.isBlank()) {
            throw new ApiException(ErrorCode.ENGINE_INDEX_FAILED, "引擎未返回文档 ID");
        }
        int chunks = response.path("chunk_count").asInt(response.path("chunks").asInt(0));
        long total = (System.nanoTime() - begin) / 1_000_000;
        return new IndexResult(engineDocId, chunks, textOr(response, "parser", "weknora"),
                textOr(response, "chunker", "weknora"), textOr(response, "embedding_model", "weknora"),
                total, total);
    }

    @Override
    public void offlineDocument(String engineDocId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("searchable", false);
        String path = kbPath + "/documents/" + engineDocId + "/offline";
        JsonNode response = send("POST", baseUrl + path, json(body), ErrorCode.ENGINE_UNAVAILABLE);
        log.info("weknora_offline doc={} ack={}", engineDocId, response != null);
    }

    @Override
    public boolean deleteDocument(String engineDocId) {
        String path = kbPath + "/documents/" + engineDocId;
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout))
                .DELETE()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.ENGINE_UNAVAILABLE, "引擎不可达，删除未确认");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "删除请求被中断");
        }
        // 404 视为已删；其余非 2xx 一律视为未确认，交由重试/DLQ 处理
        return response.statusCode() / 100 == 2 || response.statusCode() == 404;
    }

    @Override
    public List<RetrievedChunk> search(SearchQuery query) {
        if (query.engineKbIds().isEmpty()) {
            return List.of();
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode ids = body.putArray("knowledge_base_ids");
        query.engineKbIds().forEach(ids::add);
        body.put("query", query.query());
        body.put("top_k", Math.max(query.topK(), 1));
        ObjectNode filters = body.putObject("filters");
        // 召回前过滤：引擎必须按 tenant 范围召回，不能只靠 Java 事后过滤
        filters.put("tenant_id", query.tenantId());
        JsonNode response = send("POST", baseUrl + searchPath, json(body), ErrorCode.ENGINE_UNAVAILABLE);
        JsonNode items = response.path("items");
        if (!items.isArray()) {
            items = response.path("data");
        }
        List<RetrievedChunk> out = new ArrayList<>();
        Set<String> scope = new LinkedHashSet<>(query.engineKbIds());
        for (JsonNode item : items) {
            JsonNode meta = item.has("metadata") ? item.path("metadata") : item;
            String tenantId = firstText(meta, item, "tenant_id", "tenantId");
            String workspaceId = firstText(meta, item, "workspace_id", "workspaceId");
            String kbId = firstText(meta, item, "kb_id", "kbId");
            String documentId = firstText(meta, item, "document_id", "documentId");
            String engineKbId = firstText(meta, item, "engine_kb_id", "engineKbId");
            if (tenantId == null || workspaceId == null || kbId == null || documentId == null
                    || engineKbId == null) {
                // 元数据不全的候选不能用：无法证明归属，直接丢弃并留痕
                log.warn("weknora_candidate_dropped reason=missing_metadata");
                continue;
            }
            if (!query.tenantId().equals(tenantId) || !scope.contains(engineKbId)) {
                // 引擎越界召回：丢弃并留痕，绝不进入 Prompt；逐条复核仍在 ChatService 再做一次
                log.warn("weknora_candidate_dropped reason=out_of_scope tenant={} kb={}", tenantId, kbId);
                continue;
            }
            out.add(new RetrievedChunk(
                    firstText(meta, item, "chunk_id", "chunkId", "id"),
                    firstText(meta, item, "engine_doc_id", "document_id", "documentId"),
                    engineKbId, tenantId, workspaceId, kbId, documentId,
                    meta.path("document_version").asInt(meta.path("documentVersion").asInt(1)),
                    firstText(meta, item, "classification"),
                    intOrNull(meta, "page_no", "pageNo"),
                    firstText(meta, item, "section"),
                    intOrNull(meta, "char_start", "charStart"),
                    intOrNull(meta, "char_end", "charEnd"),
                    firstText(item, meta, "content", "text", "chunk"),
                    item.path("score").asDouble(meta.path("score").asDouble(0.0))));
        }
        return out;
    }

    @Override
    public Health health() {
        try {
            HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() / 100 == 2
                    ? new Health(true, "weknora", "status=" + response.statusCode())
                    : new Health(false, "weknora", "status=" + response.statusCode());
        } catch (Exception e) {
            return new Health(false, "weknora", "unreachable: " + e.getClass().getSimpleName());
        }
    }

    /** 供能力探测清理临时知识库使用；不在端口契约内，业务代码不得调用。 */
    public boolean deleteKnowledgeBase(String engineKbId) {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(baseUrl + kbPath + "/" + engineKbId))
                .timeout(timeout))
                .DELETE()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode send(String method, String url, String body, ErrorCode failure) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        authorized(builder);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            return send(builder.build(), failure);
        } catch (ApiException e) {
            throw e;
        }
    }

    private JsonNode send(HttpRequest request, ErrorCode failure) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "知识引擎超时");
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.ENGINE_UNAVAILABLE, "知识引擎不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "知识引擎请求被中断");
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("weknora_http_error status={} path={}", response.statusCode(), request.uri().getPath());
            throw new ApiException(failure, "知识引擎返回 " + response.statusCode());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node.has("data") && node.get("data").isObject() && !node.has("items")
                    ? node.get("data") : node;
        } catch (Exception e) {
            log.warn("weknora_response_unparseable path={}", request.uri().getPath());
            throw new ApiException(failure, "知识引擎响应不可解析");
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "引擎请求序列化失败");
        }
    }

    private static void writeField(ByteArrayOutputStream buf, String boundary, String name, String value) {
        write(buf, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + nz(value) + "\r\n");
    }

    private static void writeFile(ByteArrayOutputStream buf, String boundary, String name, String filename,
                                  String contentType, byte[] bytes) {
        write(buf, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + nz(filename) + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        buf.writeBytes(bytes);
        write(buf, "\r\n");
    }

    private static void writeEnd(ByteArrayOutputStream buf, String boundary) {
        write(buf, "--" + boundary + "--\r\n");
    }

    private static void write(ByteArrayOutputStream buf, String value) {
        buf.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstText(JsonNode primary, JsonNode secondary, String... fields) {
        for (String field : fields) {
            JsonNode value = primary.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
            value = secondary.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try next alias
                }
            }
        }
        return null;
    }

    private String resolveToken(KbProperties.Engine config) {
        String value = config.token();
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (config.tokenFile() != null && !config.tokenFile().isBlank()) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(config.tokenFile())).strip();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("无法读取 kb.engine.token-file: " + config.tokenFile(), e);
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("kb.engine.base-url 不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
