package com.acme.kb.p1.pipeline;

import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 重试泵：数据库是退避调度的权威，Kafka 只是“到期了”的通知通道。
 * 每 500ms 扫描 RETRY_SCHEDULED 且 next_run_at 已到期的任务，写一条确定性 eventId 的 outbox 事件，
 * 然后推进 retry_notified_at；不改变任务状态，重复通知由认领条件（next_run_at）挡住。
 *
 * 这解决了 P0 的根因：原先失败即发重试消息，消费者一到就 claim，退避形同虚设。
 *
 * <p>只在 worker 角色启用：调度与消费共用同一批 worker，扩缩容时不会出现多份泵同时推进水位。</p>
 */
@Component
@ConditionalOnProperty(prefix = "kb", name = "role", havingValue = "worker")
public class RetryPump {

    private static final Logger log = LoggerFactory.getLogger(RetryPump.class);
    private static final int BATCH = 50;

    private final PipelineCommandMapper pipeline;
    private final ObjectMapper mapper;
    private final KbProperties props;

    public RetryPump(PipelineCommandMapper pipeline, ObjectMapper mapper, KbProperties props) {
        this.pipeline = pipeline;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${kb.ingest.retry-pump-ms:500}")
    @Transactional
    public void notifyDueRetries() {
        List<Map<String, Object>> due;
        try {
            due = pipeline.lockDueRetryTasks(BATCH);
        } catch (RuntimeException e) {
            log.warn("retry_pump_scan_failed err={}", e.getClass().getSimpleName());
            return;
        }
        for (Map<String, Object> row : due) {
            try {
                notifyOne(row);
            } catch (DuplicateKeyException e) {
                // 确定性 eventId 撞唯一键：说明本轮已通知过，推进水位即可
                pipeline.markRetryNotified(str(row, "id"), Instant.now());
            } catch (Exception e) {
                log.warn("retry_pump_notify_failed task={} err={}", str(row, "id"),
                        e.getClass().getSimpleName());
            }
        }
    }

    private void notifyOne(Map<String, Object> row) throws Exception {
        String taskId = str(row, "id");
        String tenantId = str(row, "tenantId");
        String documentId = str(row, "documentId");
        int version = intOf(row.get("documentVer"), 1);
        String operationId = str(row, "operationId");
        String correlationId = str(row, "correlationId");
        int attempts = intOf(row.get("attempts"), 0);
        Instant nextRunAt = instantOf(row.get("nextRunAt"));
        ErrorCode code = ErrorCode.parse(str(row, "errorCode"), ErrorCode.ENGINE_UNAVAILABLE);

        // 确定性 eventId：同一 taskId + attempts 只可能产生一条事件
        String eventId = UUID.nameUUIDFromBytes((taskId + ":" + attempts)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Events.IngestRetry event = Events.IngestRetry.of(eventId, tenantId, documentId, version,
                operationId, correlationId, attempts + 1, Events.retryReason(code),
                nextRunAt == null ? Instant.now() : nextRunAt, code.name());

        Map<String, Object> outbox = new HashMap<>();
        outbox.put("id", UUID.randomUUID().toString());
        outbox.put("tenantId", tenantId);
        outbox.put("eventId", eventId);
        outbox.put("aggregateId", documentId);
        outbox.put("aggregateType", "document");
        outbox.put("eventType", event.eventType());
        outbox.put("topic", props.ingest().topicRetry());
        outbox.put("partitionKey", documentId);
        outbox.put("payload", mapper.writeValueAsString(event));
        outbox.put("headers", mapper.writeValueAsString(Map.of(
                "correlationId", correlationId == null ? "" : correlationId, "schemaVersion", 1)));
        outbox.put("now", Instant.now());
        pipeline.insertOutbox(outbox);
        pipeline.markRetryNotified(taskId, Instant.now());
        log.info("retry_notified task={} doc={} attempt={} notBefore={}", taskId, documentId,
                attempts + 1, nextRunAt);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
