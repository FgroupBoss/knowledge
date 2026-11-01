package com.acme.kb.p0.engine;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;

import java.util.List;

/**
 * provider=weknora 时的占位实现。P0 环境无法取得 WeKnora 镜像（见 decisions/ADR-003），
 * 任何调用都立即失败，避免"看起来接了但实际没验证"。P1 首项工作是把端口六个方法
 * 映射到官方 API 并重跑 ACL 召回前过滤用例。
 */
public class PendingWeKnoraAdapter implements KnowledgeEnginePort {

    private static final String REASON = "WeKnora 适配器尚未按官方 API 验证（ADR-003）";

    @Override
    public String name() {
        return "weknora(pending)";
    }

    @Override
    public String ensureKnowledgeBase(EngineKbSpec spec) {
        throw blocked();
    }

    @Override
    public IndexResult indexDocument(IndexRequest request) {
        throw blocked();
    }

    @Override
    public void offlineDocument(String engineDocId) {
        throw blocked();
    }

    @Override
    public boolean deleteDocument(String engineDocId) {
        throw blocked();
    }

    @Override
    public List<RetrievedChunk> search(SearchQuery query) {
        throw blocked();
    }

    @Override
    public Health health() {
        return new Health(false, "weknora", REASON);
    }

    private static ApiException blocked() {
        return new ApiException(ErrorCode.ENGINE_UNAVAILABLE, REASON);
    }
}
