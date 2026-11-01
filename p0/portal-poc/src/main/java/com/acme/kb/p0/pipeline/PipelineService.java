package com.acme.kb.p0.pipeline;

import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.TraceIds;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.PipelineCommandMapper;
import com.acme.kb.p0.repo.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务状态推进与后续事件产出。所有失败处置都写回 MySQL 再经 Outbox 投递，
 * 因此 Kafka offset 永远不是业务状态：重复投递、消费者重启都能从任务表恢复。
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineCommandMapper pipeline;
    private final TaskMapper tasks;
    private final DocumentMapper documents;
    private final P0Properties props;
    private final ObjectMapper mapper;

    public PipelineService(PipelineCommandMapper pipeline, TaskMapper tasks, DocumentMapper documents,
                           P0Properties props, ObjectMapper mapper) {
        this.pipeline = pipeline;
        this.tasks = tasks;
        this.documents = documents;
        this.props = props;
        this.mapper = mapper;
    }

    /** 幂等认领：返回 null 表示该 operation 已被他人完成或租约仍有效。 */
    @Transactional
    public TaskMapper.TaskRow claim(String operationId, String workerId) {
        Instant now = Instant.now();
        Instant lease = now.plusSeconds(props.ingest().leaseSeconds());
        int claimed = pipeline.claimTask(operationId, workerId, lease, now);
        if (claimed == 0) {
            // 租约过期的僵死任务先复位再抢，保证 worker 崩溃后仍可重放
            pipeline.reclaimExpiredTask(operationId, now, now);
            if (pipeline.claimTask(operationId, workerId, lease, now) == 0) {
                return null;
            }
        }
        TaskMapper.TaskRow task = tasks.findByOperation(operationId);
        if (task != null) {
            pipeline.markRunning(task.id(), workerId, now);
        }
        return task;
    }

    /**
     * 阶段推进（QUEUED→PARSING→INDEXING）。并发或重放已把状态推得更快时静默忽略，
     * 因为终态由 succeed/fail 统一裁定，中间态只服务可观测性。
     */
    @Transactional
    public void markStage(String documentId, String fromStatus, String toStatus) {
        if (documentId == null) {
            return;
        }
        pipeline.transitionDocument(documentId, fromStatus, toStatus, null, null, null, Instant.now());
    }

    /** 完成：文档进入目标状态（可从若干前置状态迁移），任务置 SUCCEEDED 并释放租约。 */
    @Transactional
    public void succeed(String taskId, String workerId, String documentId, String toStatus,
                        String engineDocId, String... fromStatuses) {
        Instant now = Instant.now();
        if (documentId != null && fromStatuses.length > 0) {
            transitionFromAny(documentId, toStatus, engineDocId, null, null, now, fromStatuses);
        }
        pipeline.finishTask(taskId, workerId, "SUCCEEDED", null, null, null, now, now);
    }

    /** 最后一条失败消息的真实投递坐标与原始字节，DLQ 信封据此写审计入口。 */
    public record Origin(String topic, int partition, long offset, String rawPayload) {
    }

    /** 失败处置：暂时性错误退避重试，永久错误或超过上限直接终态。 */
    @Transactional
    public void fail(String taskId, String workerId, String operationId, String documentId, int version,
                     String tenantId, String correlationId, ErrorCode code, String summary, Origin origin,
                     boolean deleteOperation) {
        Instant now = Instant.now();
        TaskMapper.TaskRow task = tasks.findByOperation(operationId);
        int attempts = task == null ? 1 : task.attempts();
        int maxAttempts = task == null ? props.ingest().maxAttempts() : task.maxAttempts();
        String safeSummary = summarize(summary);

        if (deleteOperation) {
            // 删除 Saga 的失败绝不进入库重试主题——那会把正在下线的文档重新索引出第二份有效索引。
            // 补偿入口是用户/运维重新发起 DELETE（文档状态允许），任务留痕即可。
            pipeline.finishTask(taskId, workerId, "FAILED", code.name(), safeSummary, null, now, now);
            transitionFromAny(documentId, "COMPENSATION_REQUIRED", null, code.name(), safeSummary, now,
                    "DELETING");
            log.warn("delete_saga_requires_compensation doc={} code={} attempts={}", documentId, code.name(),
                    attempts);
            return;
        }
        boolean retryable = code.transientFailure() && attempts < maxAttempts;
        if (retryable) {
            long backoff = props.ingest().backoffBaseMs() * (1L << Math.min(attempts, 6));
            Instant nextRun = now.plusMillis(backoff);
            pipeline.finishTask(taskId, workerId, "RETRY_SCHEDULED", code.name(), safeSummary, nextRun,
                    null, now);
            transitionFromAny(documentId, "QUEUED", null, code.name(), safeSummary, now,
                    "QUEUED", "PARSING", "INDEXING");
            emitOutbox(tenantId, documentId, props.ingest().topicRetry(),
                    Events.IngestRetry.of(tenantId, documentId, version, operationId, correlationId,
                            attempts + 1, Events.retryReason(code), nextRun, code.name()),
                    Events.TYPE_INGEST_RETRY);
            return;
        }
        boolean deadLettered = code.transientFailure();
        pipeline.finishTask(taskId, workerId, deadLettered ? "DEAD_LETTERED" : "FAILED", code.name(),
                safeSummary, null, now, now);
        transitionFromAny(documentId, "FAILED", null, code.name(), safeSummary, now,
                "UPLOADED", "QUEUED", "PARSING", "INDEXING", "DELETING");
        if (deadLettered) {
            emitOutbox(tenantId, documentId, props.ingest().topicDlq(),
                    Events.DlqEnvelope.of(tenantId, documentId, version, operationId, correlationId,
                            originalEvent(origin, tenantId, documentId, version, operationId, correlationId),
                            origin, List.of(props.ingest().topicRetry()), attempts, code.name(), safeSummary,
                            workerId),
                    Events.TYPE_DEAD_LETTERED);
        }
    }

    /**
     * originalEvent 必须是未修改的原始 payload；只在拿不到原始字节（非消费线程产生）时，
     * 才按同一字段重建，此时 eventId 是新值，审计要按 dlqId 而不是 eventId 追溯。
     */
    private Object originalEvent(Origin origin, String tenantId, String documentId, int version,
                                 String operationId, String correlationId) {
        if (origin != null && origin.rawPayload() != null && !origin.rawPayload().isBlank()) {
            try {
                return mapper.readTree(origin.rawPayload());
            } catch (Exception e) {
                log.warn("dlq_original_payload_unparseable doc={} topic={}", documentId, origin.topic());
            }
        }
        return Events.IngestRequested.of(tenantId, documentId, version, operationId, correlationId);
    }

    private void emitOutbox(String tenantId, String aggregateId, String topic, Object payload,
                            String eventType) {
        try {
            String json = mapper.writeValueAsString(payload);
            JsonNode node = mapper.readTree(json);
            Map<String, Object> outbox = new HashMap<>();
            outbox.put("id", UUID.randomUUID().toString());
            outbox.put("tenantId", tenantId);
            outbox.put("eventId", idOf(node, "eventId", "dlqId"));
            outbox.put("aggregateId", aggregateId);
            outbox.put("aggregateType", "document");
            outbox.put("eventType", eventType);
            outbox.put("topic", topic);
            outbox.put("partitionKey", aggregateId);
            outbox.put("payload", json);
            // traceId 取当前 MDC（API 线程与 worker 线程都已置入）；correlationId 只取事件自带的，
            // 缺失就留空——编造一个关联 ID 会让审计链路指向不存在的调用。
            Map<String, Object> eventHeaders = new HashMap<>();
            eventHeaders.put("traceId", TraceIds.current());
            eventHeaders.put("correlationId", textOrEmpty(node, "correlationId"));
            eventHeaders.put("schemaVersion", 1);
            outbox.put("headers", mapper.writeValueAsString(eventHeaders));
            outbox.put("now", Instant.now());
            pipeline.insertOutbox(outbox);
        } catch (Exception e) {
            log.error("retry_event_emit_failed aggregate={} topic={}", aggregateId, topic, e);
            throw new IllegalStateException("无法登记后续事件", e);
        }
    }

    private static String idOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode found = node.path(name);
            if (found.isTextual() && !found.asText().isBlank()) {
                return found.asText();
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String textOrEmpty(JsonNode node, String name) {
        JsonNode found = node.path(name);
        return found.isTextual() ? found.asText() : "";
    }

    private void transitionFromAny(String documentId, String toStatus, String engineDocId, String errorCode,
                                   String summary, Instant now, String... fromStatuses) {
        for (String from : fromStatuses) {
            if (from.equals(toStatus)) {
                continue;
            }
            if (pipeline.transitionDocument(documentId, from, toStatus, engineDocId, errorCode, summary,
                    now) > 0) {
                return;
            }
        }
    }

    private static String summarize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("/run/secrets/\\S+", "<secret>")
                .replaceAll("(?i)(accesskey|secretkey|password|token)\\s*[=:]\\s*\\S+", "$1=<redacted>");
        return cleaned.length() <= 480 ? cleaned : cleaned.substring(0, 480);
    }
}
