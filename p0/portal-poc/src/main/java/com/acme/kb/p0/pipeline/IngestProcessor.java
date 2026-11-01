package com.acme.kb.p0.pipeline;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.engine.KnowledgeEnginePort;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.KnowledgeBaseMapper;
import com.acme.kb.p0.repo.TaskMapper;
import com.acme.kb.p0.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 入库执行体：从 MinIO 取权威原件 → 校验 SHA-256 → 交给引擎解析索引 → 回写 engine_doc_id 与状态。
 * 解析/切分/向量化属于引擎职责，Java 侧不重复实现第二套索引事实。
 */
@Service
public class IngestProcessor {

    private static final Logger log = LoggerFactory.getLogger(IngestProcessor.class);

    private final ObjectStorageService storage;
    private final KnowledgeEnginePort engine;
    private final KnowledgeBaseMapper knowledgeBases;
    private final PipelineService pipeline;

    public IngestProcessor(ObjectStorageService storage, KnowledgeEnginePort engine,
                           KnowledgeBaseMapper knowledgeBases, PipelineService pipeline) {
        this.storage = storage;
        this.engine = engine;
        this.knowledgeBases = knowledgeBases;
        this.pipeline = pipeline;
    }

    public record Outcome(String engineDocId, int chunkCount, String parser, long indexMs) {
    }

    public Outcome index(TaskMapper.TaskRow task, DocumentMapper.Row doc, String workerId) {
        KnowledgeBaseMapper.Row kb = knowledgeBases.findById(doc.kbId());
        if (kb == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        String engineKbId = kb.engineKbId();
        if (engineKbId == null || engineKbId.isBlank()) {
            engineKbId = engine.ensureKnowledgeBase(new KnowledgeEnginePort.EngineKbSpec(kb.id(),
                    kb.tenantId(), kb.workspaceId(), kb.classification().name(), kb.aclVersion()));
            knowledgeBases.linkEngineKb(kb.id(), kb.tenantId(), engineKbId);
        }

        pipeline.markStage(doc.id(), "QUEUED", "PARSING");
        byte[] original = storage.readAll(doc.objectKey());
        String actual = Hashing.sha256Hex(original);
        if (!actual.equals(doc.sha256())) {
            // 权威原件与记录不一致：永久错误，需人工介入，绝不索引可疑内容
            throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH,
                    "原件摘要与登记值不一致，已拒绝索引");
        }

        KnowledgeEnginePort.IndexRequest request = new KnowledgeEnginePort.IndexRequest(
                doc.kbId(), engineKbId, doc.tenantId(), doc.workspaceId(), doc.id(), doc.version(),
                doc.displayName(), doc.mimeType(), doc.extension(), doc.sha256(), doc.classification(),
                kb.aclVersion(), original, task.operationId(),
                task.correlationId() == null ? UUID.randomUUID().toString() : task.correlationId());

        pipeline.markStage(doc.id(), "PARSING", "INDEXING");
        long begin = System.nanoTime();
        KnowledgeEnginePort.IndexResult result = engine.indexDocument(request);
        long indexMs = (System.nanoTime() - begin) / 1_000_000;
        log.info("ingest_done doc={} v={} engineDoc={} chunks={} parser={} ms={}", doc.id(), doc.version(),
                result.engineDocId(), result.chunkCount(), result.parser(), indexMs);
        return new Outcome(result.engineDocId(), result.chunkCount(), result.parser(), indexMs);
    }

    /** 删除 Saga：冻结检索 → 引擎物理删除并确认可下线 → 原件按保留策略处置。 */
    public boolean delete(DocumentMapper.Row doc, boolean purge) {
        String engineDocId = doc.engineDocId();
        if (engineDocId != null && !engineDocId.isBlank()) {
            engine.offlineDocument(engineDocId);
            if (!engine.deleteDocument(engineDocId)) {
                throw new ApiException(ErrorCode.ENGINE_INDEX_FAILED, "引擎索引未能确认删除");
            }
        }
        if (purge) {
            if (!storage.delete(doc.objectKey())) {
                throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "原件删除失败");
            }
        }
        return true;
    }
}
