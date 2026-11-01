package com.acme.kb.p1.pipeline;

import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 事务 Outbox 发布器：以 FOR UPDATE SKIP LOCKED 领取未发布事件，投递 Kafka 成功后才标记，
 * 并把文档状态从 UPLOADED 推进到 QUEUED。投递成功但标记失败只会造成重复投递（消费端按 eventId 去重），
 * 不会出现"DB 已提交而事件永久丢失"。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final PipelineCommandMapper pipeline;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final KbProperties props;

    public OutboxPublisher(PipelineCommandMapper pipeline, KafkaTemplate<String, String> kafka,
                           ObjectMapper mapper, KbProperties props) {
        this.pipeline = pipeline;
        this.kafka = kafka;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${kb.ingest.outbox-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<Map<String, Object>> rows;
        try {
            rows = pipeline.lockUnpublished(props.ingest().outboxBatch());
        } catch (RuntimeException e) {
            log.warn("outbox_lock_failed err={}", e.toString());
            return;
        }
        for (Map<String, Object> row : rows) {
            String id = str(row, "id");
            String topic = str(row, "topic");
            String key = str(row, "partitionKey");
            String payload = str(row, "payload");
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
                addHeader(record, TraceIds.KAFKA_HEADER_EVENT_ID, str(row, "eventId"));
                addHeader(record, TraceIds.KAFKA_HEADER_SCHEMA_VERSION,
                        String.valueOf(row.get("schemaVersion")));
                Object headers = row.get("headers");
                if (headers != null) {
                    Map<String, Object> map = mapper.readValue(String.valueOf(headers), Map.class);
                    addHeader(record, TraceIds.KAFKA_HEADER_TRACE, text(map.get("traceId")));
                    addHeader(record, TraceIds.KAFKA_HEADER_CORRELATION, text(map.get("correlationId")));
                    addHeader(record, TraceIds.KAFKA_HEADER_ACTOR, text(map.get("actorId")));
                }
                kafka.send(record).get(10, java.util.concurrent.TimeUnit.SECONDS);
                pipeline.markPublished(id, Instant.now());
                advanceDocumentState(row);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pipeline.markPublishFailure(id, "interrupted");
                return;
            } catch (Exception e) {
                log.warn("outbox_publish_failed id={} topic={} err={}", id, topic, e.toString());
                pipeline.markPublishFailure(id, e.getClass().getSimpleName());
            }
        }
    }

    /** 事件已入队：UPLOADED → QUEUED。迁移失败说明已被并发路径推进，忽略。 */
    private void advanceDocumentState(Map<String, Object> row) {
        if (!Events.TYPE_INGEST_REQUESTED.equals(str(row, "eventType"))) {
            return;
        }
        pipeline.transitionDocument(str(row, "aggregateId"), "UPLOADED", "QUEUED", null, null, null,
                Instant.now());
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 空值不写成 Kafka header：读侧 `header != null` 才可信，空串会被当成"有追踪上下文"。 */
    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null && !value.isBlank()) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
