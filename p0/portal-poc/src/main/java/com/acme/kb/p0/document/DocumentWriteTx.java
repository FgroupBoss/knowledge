package com.acme.kb.p0.document;

import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.pipeline.Events;
import com.acme.kb.p0.repo.PipelineCommandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 入库入口的事务写入：document + ingest_task + outbox_event + 幂等记录在同一 MySQL 事务内完成，
 * 因此不存在"DB 已提交但事件丢失"或"事件已发但 DB 无记录"的中间态。
 */
@Service
public class DocumentWriteTx {

    private static final Logger log = LoggerFactory.getLogger(DocumentWriteTx.class);
    public static final String SCOPE_UPLOAD = "document-upload";
    public static final String SCOPE_RETRY = "document-retry";

    private final PipelineCommandMapper pipeline;
    private final ObjectMapper mapper;
    private final P0Properties props;

    public DocumentWriteTx(PipelineCommandMapper pipeline, ObjectMapper mapper, P0Properties props) {
        this.pipeline = pipeline;
        this.mapper = mapper;
        this.props = props;
    }

    public record Created(String documentId, String taskId, String operationId, String eventId, int version) {
    }

    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Created createDocument(Actor actor, String documentId, String workspaceId, String kbId,
                                  String classification, UploadValidator.Spool spool, String objectKey,
                                  String idempotencyKey, int version) {
        Instant now = Instant.now();
        String operationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", documentId);
        doc.put("tenantId", actor.tenantId());
        doc.put("workspaceId", workspaceId);
        doc.put("kbId", kbId);
        doc.put("displayName", spool.displayName());
        doc.put("version", version);
        doc.put("objectKey", objectKey);
        doc.put("sha256", spool.sha256());
        doc.put("sizeBytes", spool.size());
        doc.put("mimeType", spool.mimeType());
        doc.put("extension", spool.extension());
        doc.put("classification", classification);
        doc.put("status", "UPLOADED");
        doc.put("createdBy", actor.userId());
        doc.put("now", now);
        pipeline.insertDocument(doc);

        Map<String, Object> task = new HashMap<>();
        task.put("id", taskId);
        task.put("tenantId", actor.tenantId());
        task.put("documentId", documentId);
        task.put("documentVersion", version);
        task.put("operationId", operationId);
        task.put("taskType", "INGEST");
        task.put("maxAttempts", props.ingest().maxAttempts());
        task.put("nextRunAt", now);
        task.put("correlationId", correlationId);
        task.put("now", now);
        pipeline.insertTask(task);

        Events.IngestRequested event = Events.IngestRequested.of(actor.tenantId(), documentId, version,
                operationId, correlationId);
        Map<String, Object> outbox = new HashMap<>();
        outbox.put("id", UUID.randomUUID().toString());
        outbox.put("tenantId", actor.tenantId());
        outbox.put("eventId", event.eventId());
        outbox.put("aggregateId", documentId);
        outbox.put("aggregateType", "document");
        outbox.put("eventType", event.eventType());
        outbox.put("topic", props.ingest().topicRequested());
        outbox.put("partitionKey", documentId);
        outbox.put("payload", writeJson(event));
        outbox.put("headers", writeJson(Map.of("traceId", nullToEmpty(org.slf4j.MDC.get("traceId")),
                "correlationId", correlationId, "actorId", actor.userId(), "schemaVersion", 1)));
        outbox.put("now", now);
        pipeline.insertOutbox(outbox);

        recordIdempotency(actor, SCOPE_UPLOAD, idempotencyKey, documentId,
                uploadDigest(kbId, spool));
        return new Created(documentId, taskId, operationId, event.eventId(), version);
    }

    /** 重试：同一事务内完成状态迁移 + 新 operationId 任务 + 事件，避免半程崩溃。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public Created enqueueRetry(Actor actor, String documentId, int version, String tenantId,
                                String idempotencyKey, String fromStatus) {
        Instant now = Instant.now();
        if (pipeline.transitionDocument(documentId, fromStatus, "UPLOADED", null, null, null, now) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "文档状态已变化，请重新查询");
        }
        String operationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        Map<String, Object> task = new HashMap<>();
        task.put("id", taskId);
        task.put("tenantId", tenantId);
        task.put("documentId", documentId);
        task.put("documentVersion", version);
        task.put("operationId", operationId);
        task.put("taskType", "REINDEX");
        task.put("maxAttempts", props.ingest().maxAttempts());
        task.put("nextRunAt", now);
        task.put("correlationId", correlationId);
        task.put("now", now);
        pipeline.insertTask(task);

        Events.IngestRequested event = Events.IngestRequested.of(tenantId, documentId, version, operationId,
                correlationId);
        Map<String, Object> outbox = new HashMap<>();
        outbox.put("id", UUID.randomUUID().toString());
        outbox.put("tenantId", tenantId);
        outbox.put("eventId", event.eventId());
        outbox.put("aggregateId", documentId);
        outbox.put("aggregateType", "document");
        outbox.put("eventType", event.eventType());
        outbox.put("topic", props.ingest().topicRequested());
        outbox.put("partitionKey", documentId);
        outbox.put("payload", writeJson(event));
        outbox.put("headers", writeJson(Map.of("traceId", nullToEmpty(org.slf4j.MDC.get("traceId")),
                "correlationId", correlationId, "actorId", actor.userId(), "schemaVersion", 1)));
        outbox.put("now", now);
        pipeline.insertOutbox(outbox);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            recordIdempotency(actor, SCOPE_RETRY, idempotencyKey, taskId, "retry:" + documentId + ":" + version);
        }
        return new Created(documentId, taskId, operationId, event.eventId(), version);
    }

    /** 删除：同事务写 DELETING 状态 + 删除任务 + 删除事件，保证 Saga 可重放。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public Created enqueueDelete(Actor actor, String documentId, int version, String tenantId,
                                 String fromStatus, boolean purge, String reason) {
        Instant now = Instant.now();
        String operationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        int moved = pipeline.transitionDocument(documentId, fromStatus, "DELETING", null, null, null, now);
        if (moved == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "文档状态不允许删除，请重试");
        }
        Map<String, Object> task = new HashMap<>();
        task.put("id", taskId);
        task.put("tenantId", tenantId);
        task.put("documentId", documentId);
        task.put("documentVersion", version);
        task.put("operationId", operationId);
        task.put("taskType", "DELETE");
        task.put("maxAttempts", props.ingest().maxAttempts());
        task.put("nextRunAt", now);
        task.put("correlationId", correlationId);
        task.put("now", now);
        pipeline.insertTask(task);

        Events.DeleteRequested event = Events.DeleteRequested.of(tenantId, documentId, version, operationId,
                correlationId, actor.userId(), purge, reason);
        Map<String, Object> outbox = new HashMap<>();
        outbox.put("id", UUID.randomUUID().toString());
        outbox.put("tenantId", tenantId);
        outbox.put("eventId", event.eventId());
        outbox.put("aggregateId", documentId);
        outbox.put("aggregateType", "document");
        outbox.put("eventType", event.eventType());
        outbox.put("topic", props.ingest().topicDelete());
        outbox.put("partitionKey", documentId);
        outbox.put("payload", writeJson(event));
        outbox.put("headers", writeJson(Map.of("traceId", nullToEmpty(org.slf4j.MDC.get("traceId")),
                "correlationId", correlationId, "actorId", actor.userId(), "schemaVersion", 1)));
        outbox.put("now", now);
        pipeline.insertOutbox(outbox);
        return new Created(documentId, taskId, operationId, event.eventId(), version);
    }

    private void recordIdempotency(Actor actor, String scope, String key, String resourceId, String digest) {
        if (key == null || key.isBlank()) {
            return;
        }
        pipeline.insertIdempotency(UUID.randomUUID().toString(), actor.tenantId(), actor.userId(), scope,
                key, resourceId, Hashing.sha256Hex(digest), Instant.now());
    }

    /** 幂等请求指纹：同键不同内容即冲突，服务端据此拒绝覆盖。 */
    public static String uploadDigest(String kbId, UploadValidator.Spool spool) {
        return "kb=" + kbId + "|sha=" + spool.sha256() + "|size=" + spool.size()
                + "|name=" + spool.displayName();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("outbox_payload_serialize_failed", e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "事件序列化失败");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
