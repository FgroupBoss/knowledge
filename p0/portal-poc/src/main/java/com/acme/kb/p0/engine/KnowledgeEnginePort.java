package com.acme.kb.p0.engine;

import java.util.List;

/**
 * 业务侧与知识引擎的唯一边界。业务代码不得出现引擎 URL、引擎内部表字段或重试策略。
 * 引擎返回的每个 chunk 都必须携带归属元数据，供 Java 侧逐条复核。
 */
public interface KnowledgeEnginePort {

    String name();

    /** 幂等创建/更新引擎侧知识集合，返回 engine_kb_id；绝不把引擎 ID 当业务主键。 */
    String ensureKnowledgeBase(EngineKbSpec spec);

    IndexResult indexDocument(IndexRequest request);

    /** 冻结该文档检索（删除 Saga 第一步：先不可召回，再物理删除）。 */
    void offlineDocument(String engineDocId);

    /** 物理删除引擎索引；返回后必须已确认不可检索。 */
    boolean deleteDocument(String engineDocId);

    /**
     * 检索。scope 由服务端授权结果构造：engineKbIds 只可能来自授权集合，
     * tenantId 再作二次约束（引擎侧召回前过滤）。
     * 返回顺序必须是 score 从高到低：PromptBuilder 依赖这个顺序在预算耗尽时截断尾部，
     * 若引擎乱序返回，装不下的就不是最弱的证据。
     */
    List<RetrievedChunk> search(SearchQuery query);

    Health health();

    record EngineKbSpec(String kbId, String tenantId, String workspaceId, String classification,
                        long aclVersion) {
    }

    record IndexRequest(String kbId, String engineKbId, String tenantId, String workspaceId,
                        String documentId, int documentVersion, String displayName,
                        String mimeType, String extension, String sha256, String classification,
                        long aclVersion, byte[] original, String operationId, String correlationId) {
    }

    record IndexResult(String engineDocId, int chunkCount, String parser, String chunker,
                       String embeddingModel, long parseMs, long indexMs) {
    }

    record SearchQuery(String tenantId, List<String> engineKbIds, String query, int topK,
                       int candidateMultiplier) {
    }

    record RetrievedChunk(String engineChunkId, String engineDocId, String engineKbId,
                          String tenantId, String workspaceId, String kbId, String documentId,
                          int documentVersion, String classification, Integer pageNo, String section,
                          Integer charStart, Integer charEnd, String content, double score) {
    }

    record Health(boolean available, String provider, String detail) {
    }
}
