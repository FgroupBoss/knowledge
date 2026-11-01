package com.acme.kb.p1.engine;

import com.acme.kb.p1.common.Hashing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 引擎能力探测（#1 前置门禁）：
 * 1) 创建临时知识库；
 * 2) 写入一份带完整归属元数据的测试文档；
 * 3) 用"越界 scope"检索必须 0 条；
 * 4) 用正确 scope 检索必须能召回该文档；
 * 4) 任一失败 → 抛 {@code ENGINE_ACL_UNSUPPORTED}，拒绝启动。
 *
 * <p>不能以"仅结果后过滤"上线：如果引擎不支持召回前范围过滤，越界内容会进入模型上下文，
 * Java 侧的事后复核只能阻止它们进入最终引用，阻止不了模型已经读过。</p>
 */
public final class WeKnoraCapabilityProbe {

    private static final Duration INDEX_WAIT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(2);

    private WeKnoraCapabilityProbe() {
    }

    public static void run(WeKnoraAdapter engine) {
        String tenantId = "probe-tenant-" + UUID.randomUUID();
        String workspaceId = "probe-ws-" + UUID.randomUUID();
        String kbId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        String marker = "KBPROBE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String engineKbId = null;
        String engineDocId = null;
        try {
            engineKbId = engine.ensureKnowledgeBase(new KnowledgeEnginePort.EngineKbSpec(kbId, tenantId,
                    workspaceId, "INTERNAL", 1));
            byte[] body = ("P1 capability probe.\n" + marker
                    + " 是本文件唯一标识，用于验证召回与范围过滤。\n").getBytes(StandardCharsets.UTF_8);
            KnowledgeEnginePort.IndexResult result = engine.indexDocument(
                    new KnowledgeEnginePort.IndexRequest(kbId, engineKbId, tenantId, workspaceId, documentId,
                            1, "probe.txt", "text/plain", "txt", Hashing.sha256Hex(new String(body,
                            StandardCharsets.UTF_8)), "INTERNAL", 1, body, "probe-op",
                            "probe-correlation"));
            engineDocId = result.engineDocId();

            boolean searchable = awaitSearchable(engine, engineKbId, tenantId, marker);
            if (!searchable) {
                fail("正确范围检索在 " + INDEX_WAIT.toSeconds() + "s 内没有召回探测文档");
            }

            List<KnowledgeEnginePort.RetrievedChunk> outOfScope = engine.search(
                    new KnowledgeEnginePort.SearchQuery("probe-outside-" + UUID.randomUUID(),
                            List.of(engineKbId), marker, 5, 1));
            if (!outOfScope.isEmpty()) {
                fail("越界 scope 检索返回了 " + outOfScope.size() + " 条结果，引擎未执行召回前过滤");
            }

            List<KnowledgeEnginePort.RetrievedChunk> inScope = engine.search(
                    new KnowledgeEnginePort.SearchQuery(tenantId, List.of(engineKbId), marker, 5, 1));
            if (inScope.isEmpty()) {
                fail("正确 scope 检索没有返回探测文档");
            }
            boolean documentMatched = inScope.stream().anyMatch(c -> documentId.equals(c.documentId()));
            if (!documentMatched) {
                fail("召回结果缺少 document_id 元数据，无法在 Java 侧逐条复核");
            }
        } finally {
            if (engineDocId != null) {
                try {
                    engine.deleteDocument(engineDocId);
                } catch (RuntimeException ignored) {
                    // 清理失败不改变探测结论，但临时库会留下痕迹
                }
            }
            if (engineKbId != null) {
                engine.deleteKnowledgeBase(engineKbId);
            }
        }
    }

    private static boolean awaitSearchable(WeKnoraAdapter engine, String engineKbId, String tenantId,
                                           String marker) {
        Instant deadline = Instant.now().plus(INDEX_WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                List<KnowledgeEnginePort.RetrievedChunk> hits = engine.search(
                        new KnowledgeEnginePort.SearchQuery(tenantId, List.of(engineKbId), marker, 5, 1));
                if (!hits.isEmpty()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 引擎可能还在建索引，继续轮询
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void fail(String detail) {
        throw new IllegalStateException("ENGINE_ACL_UNSUPPORTED: " + detail);
    }
}
