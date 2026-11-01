package com.acme.kb.p1.pipeline;

import com.acme.kb.p1.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka 事件载荷，字段与 contracts/kafka/*.schema.json 一一对应。
 * 只传业务 ID 与必要元数据，绝不携带原文或凭据。
 */
public final class Events {

    private Events() {
    }

    public static final String TYPE_INGEST_REQUESTED = "DOCUMENT_INGEST_REQUESTED";
    public static final String TYPE_INGEST_RETRY = "DOCUMENT_INGEST_RETRY";
    public static final String TYPE_DELETE_REQUESTED = "DOCUMENT_DELETE_REQUESTED";
    public static final String TYPE_DEAD_LETTERED = "INGEST_DEAD_LETTERED";

    /**
     * 重试原因归类：契约把 retryReason 收成有限枚举，运维只需知道"为什么值得再试"，
     * 精确错误码留在 lastErrorCode，避免枚举随 ErrorCode 扩张而频繁破坏兼容。
     */
    public static String retryReason(ErrorCode code) {
        return switch (code) {
            case ENGINE_TIMEOUT -> "ENGINE_TIMEOUT";
            case ENGINE_UNAVAILABLE, ENGINE_INDEX_FAILED -> "ENGINE_UNAVAILABLE";
            case MODEL_UNAVAILABLE, CONCURRENCY_LIMITED -> "MODEL_QUEUE_FULL";
            default -> "TRANSIENT_NETWORK";
        };
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestRequested(String eventId, String eventType, int schemaVersion, Instant occurredAt,
                                  String tenantId, String documentId, int documentVersion,
                                  String operationId, String correlationId) {

        public static IngestRequested of(String tenantId, String documentId, int version,
                                         String operationId, String correlationId) {
            return new IngestRequested(UUID.randomUUID().toString(), TYPE_INGEST_REQUESTED, 1,
                    Instant.now(), tenantId, documentId, version, operationId, correlationId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestRetry(String eventId, String eventType, int schemaVersion, Instant occurredAt,
                              String tenantId, String documentId, int documentVersion,
                              String operationId, String correlationId, int attempt, String retryReason,
                              Instant notBefore, String lastErrorCode) {

        public static IngestRetry of(String tenantId, String documentId, int version, String operationId,
                                     String correlationId, int attempt, String reason, Instant notBefore,
                                     String lastErrorCode) {
            return of(UUID.randomUUID().toString(), tenantId, documentId, version, operationId,
                    correlationId, attempt, reason, notBefore, lastErrorCode);
        }

        /**
         * RetryPump 使用确定性 eventId（taskId + attempts）。重复通知只会撞 outbox 唯一键，
         * 不会在同一失败轮次产生两条可消费事件；消费端账本也因此能识别重复。
         */
        public static IngestRetry of(String eventId, String tenantId, String documentId, int version,
                                     String operationId, String correlationId, int attempt, String reason,
                                     Instant notBefore, String lastErrorCode) {
            return new IngestRetry(eventId, TYPE_INGEST_RETRY, 1, Instant.now(),
                    tenantId, documentId, version, operationId, correlationId, attempt, reason, notBefore,
                    lastErrorCode);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeleteRequested(String eventId, String eventType, int schemaVersion, Instant occurredAt,
                                  String tenantId, String documentId, int documentVersion,
                                  String operationId, String correlationId, String actorId, boolean purge,
                                  String reason) {

        public static DeleteRequested of(String tenantId, String documentId, int version, String operationId,
                                         String correlationId, String actorId, boolean purge, String reason) {
            return new DeleteRequested(UUID.randomUUID().toString(), TYPE_DELETE_REQUESTED, 1, Instant.now(),
                    tenantId, documentId, version, operationId, correlationId, actorId, purge, reason);
        }
    }

    /**
     * 死信信封：保留原始投递坐标与未修改的原始 payload，重放必须经审计入口。
     * tenant/document/operation/correlation 是从 originalEvent 冗余出的检索字段，DLQ 巡检不必解析原文。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DlqEnvelope(String dlqId, String eventType, int schemaVersion, Instant occurredAt,
                              String tenantId, String documentId, int documentVersion, String operationId,
                              String correlationId, String originalTopic, int originalPartition,
                              long originalOffset, int failureCount, String finalErrorCode,
                              String lastErrorSummary, List<String> retryTopics, Object originalEvent,
                              String recordedBy, String replayState) {

        public static DlqEnvelope of(String tenantId, String documentId, int documentVersion,
                                     String operationId, String correlationId, Object originalEvent,
                                     PipelineService.Origin origin, List<String> retryTopics,
                                     int failureCount, String finalErrorCode, String lastErrorSummary,
                                     String recordedBy) {
            return new DlqEnvelope(UUID.randomUUID().toString(), TYPE_DEAD_LETTERED, 1, Instant.now(),
                    tenantId, documentId, documentVersion, operationId, correlationId,
                    origin == null ? "unknown" : origin.topic(),
                    origin == null ? -1 : origin.partition(),
                    origin == null ? -1L : origin.offset(),
                    failureCount, finalErrorCode, lastErrorSummary, retryTopics, originalEvent,
                    recordedBy, "UNREVIEWED");
        }
    }
}
