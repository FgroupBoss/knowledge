package com.acme.kb.p0.engine;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.engine.parsing.DocumentParser;
import com.acme.kb.p0.engine.parsing.ParsedDocument;
import com.acme.kb.p0.repo.EngineStoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 参考知识引擎：在业务库内模拟引擎自己的索引存储与检索，用于在没有可用引擎镜像时
 * 仍能把"召回前范围过滤 + 逐条复核"这条安全链路跑到可验证程度。
 * 它不是 WeKnora 的替身：真实引擎的召回前 ACL 能力必须由 WeKnoraEngine 复测。
 */
public class StubKnowledgeEngine implements KnowledgeEnginePort {

    private static final Logger log = LoggerFactory.getLogger(StubKnowledgeEngine.class);
    private static final int EMBED_BATCH = 8;

    private final EngineStoreMapper store;
    private final DocumentParser parser;
    private final EmbeddingProvider embeddings;
    private final ObjectMapper mapper;
    private final P0Properties.Engine config;

    public StubKnowledgeEngine(EngineStoreMapper store, DocumentParser parser,
                               EmbeddingProvider embeddings, ObjectMapper mapper,
                               P0Properties.Engine config) {
        this.store = store;
        this.parser = parser;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public String ensureKnowledgeBase(EngineKbSpec spec) {
        String engineKbId = "ekb-" + spec.kbId();
        store.upsertKb(engineKbId, spec.tenantId(), spec.workspaceId(), spec.kbId(),
                spec.classification(), spec.aclVersion(), Instant.now());
        return engineKbId;
    }

    @Override
    public IndexResult indexDocument(IndexRequest request) {
        long parseBegin = System.nanoTime();
        ParsedDocument parsed = parser.parse(request.original(), request.displayName(), request.mimeType());
        long parseMs = (System.nanoTime() - parseBegin) / 1_000_000;
        List<Chunk> chunks = Chunker.chunk(parsed, config.chunkSize(), config.chunkOverlap());
        long indexBegin = System.nanoTime();

        String engineDocId = "edoc-" + request.documentId() + "-v" + request.documentVersion();
        // 重放保护：同一 document/version 重跑先清旧 chunk，绝不留下第二份有效索引。
        store.deleteChunks(engineDocId);
        String parseMeta = writeMeta(parsed, chunks.size(), request);
        store.upsertDocument(engineDocId, request.engineKbId(), request.tenantId(), request.workspaceId(),
                request.kbId(), request.documentId(), request.documentVersion(), request.sha256(),
                parseMeta, Instant.now());

        List<String> texts = chunks.stream().map(Chunk::text).toList();
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<Chunk> batch = chunks.subList(i, Math.min(chunks.size(), i + EMBED_BATCH));
            List<float[]> vectors = embeddings.embedBatch(batch.stream().map(Chunk::text).toList());
            for (int j = 0; j < batch.size(); j++) {
                Chunk c = batch.get(j);
                store.insertChunk(UUID.randomUUID().toString(), engineDocId + "#" + c.chunkNo(),
                        engineDocId, request.engineKbId(), request.tenantId(), request.workspaceId(),
                        request.kbId(), request.documentId(), request.documentVersion(),
                        request.classification(), c.chunkNo(), c.pageNo(), c.section(), c.charStart(),
                        c.charEnd(), c.text(), Hashing.sha256Hex(c.text()),
                        EngineCodec.toBytes(vectors.get(j)), embeddings.model(), c.tokenEstimate(),
                        Instant.now());
            }
        }
        long indexMs = (System.nanoTime() - indexBegin) / 1_000_000;
        log.info("indexed doc={} v={} chunks={} parser={}", request.documentId(),
                request.documentVersion(), chunks.size(), parsed.parser());
        return new IndexResult(engineDocId, chunks.size(), parsed.parser(), Chunker.CHUNKER_ID,
                embeddings.model(), parseMs, indexMs);
    }

    @Override
    public void offlineDocument(String engineDocId) {
        store.setSearchable(engineDocId, false);
    }

    @Override
    public boolean deleteDocument(String engineDocId) {
        store.setSearchable(engineDocId, false);
        store.deleteChunks(engineDocId);
        store.deleteDocument(engineDocId);
        return store.findChunk(engineDocId + "#0") == null;
    }

    @Override
    public List<RetrievedChunk> search(SearchQuery query) {
        if (query.engineKbIds().isEmpty()) {
            return List.of();
        }
        List<EngineStoreMapper.ChunkRow> candidates = new ArrayList<>(
                store.loadSearchableChunks(query.tenantId(), query.engineKbIds()));
        if (config.leakOutOfScope()) {
            candidates.addAll(store.loadChunksOutsideScope(query.engineKbIds(), 5));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        float[] qv = embeddings.embed(query.query());
        Set<String> qTerms = terms(query.query());
        int limit = Math.max(query.topK(), 1);

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (EngineStoreMapper.ChunkRow row : candidates) {
            double vec = clamp(EngineCodec.cosine(qv, EngineCodec.toVector(row.embedding())));
            double kw = keywordScore(qTerms, row.content());
            double fused = 0.6 * vec + 0.4 * kw;
            if (fused < config.scoreThreshold()) {
                continue;
            }
            scored.add(new Scored(row, fused, vec, kw));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.fused()).reversed());
        List<RetrievedChunk> out = new ArrayList<>(limit);
        for (Scored s : scored.subList(0, Math.min(limit, scored.size()))) {
            EngineStoreMapper.ChunkRow r = s.row();
            out.add(new RetrievedChunk(r.engineChunkId(), r.engineDocId(), r.engineKbId(), r.tenantId(),
                    r.workspaceId(), r.kbId(), r.documentId(), r.documentVer(), r.classification(),
                    r.pageNo(), r.section(), r.charStart(), r.charEnd(), r.content(), s.fused()));
        }
        return out;
    }

    @Override
    public Health health() {
        try {
            long chunks = store.countChunks();
            long docs = store.countSearchableDocuments();
            return new Health(true, "stub", "chunks=" + chunks + " searchableDocs=" + docs
                    + " embedding=" + embeddings.kind());
        } catch (RuntimeException e) {
            return new Health(false, "stub", "unavailable");
        }
    }

    private String writeMeta(ParsedDocument parsed, int chunkCount, IndexRequest request) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "parser", parsed.parser(),
                    "chunker", Chunker.CHUNKER_ID,
                    "chunkSize", config.chunkSize(),
                    "chunkOverlap", config.chunkOverlap(),
                    "embeddingModel", embeddings.model(),
                    "embeddingKind", embeddings.kind(),
                    "chunks", chunkCount,
                    "chars", parsed.textChars(),
                    "meta", parsed.meta(),
                    "operationId", request.operationId()));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "解析元数据序列化失败");
        }
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    private static double keywordScore(Set<String> queryTerms, String content) {
        if (queryTerms.isEmpty() || content == null) {
            return 0.0;
        }
        Set<String> docTerms = terms(content);
        int hits = 0;
        for (String t : queryTerms) {
            if (docTerms.contains(t)) {
                hits++;
            }
        }
        return (double) hits / queryTerms.size();
    }

    private static Set<String> terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean cjk = c >= 0x4E00 && c <= 0x9FFF;
            if (cjk) {
                if (!word.isEmpty()) {
                    out.add(word.toString());
                    word.setLength(0);
                }
                if (i + 1 < s.length()) {
                    out.add(s.substring(i, i + 2));
                }
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (!word.isEmpty()) {
                out.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            out.add(word.toString());
        }
        return out;
    }

    private record Scored(EngineStoreMapper.ChunkRow row, double fused, double vector, double keyword) {
    }
}
