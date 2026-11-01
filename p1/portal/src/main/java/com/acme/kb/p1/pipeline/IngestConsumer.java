package com.acme.kb.p1.pipeline;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.DocumentMapper;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import com.acme.kb.p1.repo.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 入库/删除事件消费者。顺序固定为：登记消费账本 → 租约认领 → 执行 → 持久化业务状态 → 手动提交 offset。
 * 因此 offset 只在状态落库后才前进，重复投递由 eventId 账本与 operation 租约双重挡住。
 *
 * <p>只在 worker 角色启用：API 进程不消费 Kafka，避免扩容 API 时争抢同一分区。</p>
 */
@Component
@ConditionalOnProperty(prefix = "kb", name = "role", havingValue = "worker")
public class IngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestConsumer.class);

    private final ObjectMapper mapper;
    private final PipelineCommandMapper ledger;
    private final PipelineService pipeline;
    private final IngestProcessor processor;
    private final DocumentMapper documents;
    private final TaskMapper tasks;
    private final KbProperties props;
    private final String workerId;

    public IngestConsumer(ObjectMapper mapper, PipelineCommandMapper ledger, PipelineService pipeline,
                          IngestProcessor processor, DocumentMapper documents, TaskMapper tasks,
                          KbProperties props, WorkerIdentity worker) {
        this.mapper = mapper;
        this.ledger = ledger;
        this.pipeline = pipeline;
        this.processor = processor;
        this.documents = documents;
        this.tasks = tasks;
        this.props = props;
        this.workerId = worker.id();
    }

    @KafkaListener(topics = {
            "${kb.ingest.topic-requested:kb.ingest.requested.v1}",
            "${kb.ingest.topic-retry:kb.ingest.retry.v1}" },
            id = "p1-ingest", groupId = "${kb.ingest.consumer-group:p1-ingest}",
            clientIdPrefix = "p1-ingest")
    public void onIngest(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, false);
    }

    @KafkaListener(topics = "${kb.ingest.topic-delete:kb.document.delete.v1}",
            id = "p1-delete", groupId = "${kb.ingest.delete-consumer-group:p1-delete}",
            clientIdPrefix = "p1-delete")
    public void onDelete(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, true);
    }

    private void handle(ConsumerRecord<String, String> record, Acknowledgment ack, boolean deleteOperation) {
        JsonNode node;
        try {
            node = mapper.readTree(record.value());
        } catch (Exception e) {
            // 毒消息：连字段都读不出来就无法写业务状态，提交 offset 以免卡住分区；
            // 该文档会一直停在上传态，由 backlog 的 unconvergedDocuments 暴露出来，绝不静默丢弃。
            log.error("unparseable_event topic={} partition={} offset={} — 已提交，等待 backlog 对账",
                    record.topic(), record.partition(), record.offset());
            ack.acknowledge();
            return;
        }
        String eventId = node.path("eventId").asText("");
        String operationId = node.path("operationId").asText("");
        String documentId = node.path("documentId").asText("");
        String tenantId = node.path("tenantId").asText("");
        int version = node.path("documentVersion").asInt(1);
        String correlationId = node.path("correlationId").asText(null);
        if (eventId.isBlank() || operationId.isBlank()) {
            ack.acknowledge();
            return;
        }

        // 重试消息早到：next_run_at 未到期就 ack，让 RetryPump 在到期后再次通知。
        // 不在这里 sleep，也不阻塞分区。
        if (record.topic().equals(props.ingest().topicRetry())) {
            TaskMapper.TaskRow pending = tasks.findByOperation(operationId);
            if (pending != null && pending.nextRunAt() != null && pending.nextRunAt().isAfter(Instant.now())) {
                ledger.recordConsumption(eventId, record.topic(), operationId, record.partition(),
                        record.offset(), "SKIPPED_NOT_DUE", Instant.now());
                ack.acknowledge();
                return;
            }
        }

        Map<String, Object> prior = ledger.consumptionOf(eventId);
        if (prior != null && "SUCCEEDED".equals(String.valueOf(prior.get("outcome")))) {
            ledger.recordConsumption(eventId, record.topic(), operationId, record.partition(),
                    record.offset(), "SUCCEEDED", Instant.now());
            log.info("duplicate_event_skipped eventId={} operation={}", eventId, operationId);
            ack.acknowledge();
            return;
        }
        ledger.recordConsumption(eventId, record.topic(), operationId, record.partition(), record.offset(),
                "RECEIVED", Instant.now());

        TaskMapper.TaskRow task = pipeline.claim(operationId, workerId);
        if (task == null) {
            ledger.updateConsumptionOutcome(eventId, "SKIPPED_NOT_CLAIMABLE", Instant.now());
            ack.acknowledge();
            return;
        }
        DocumentMapper.Row doc = documents.findById(documentId);
        if (doc == null || "DELETED".equals(doc.status())) {
            pipeline.succeed(task.id(), workerId, null, null, null);
            ledger.updateConsumptionOutcome(eventId, "SUCCEEDED_NOOP", Instant.now());
            ack.acknowledge();
            return;
        }

        try {
            if (deleteOperation) {
                boolean purge = node.path("purge").asBoolean(false);
                processor.delete(doc, purge);
                pipeline.succeed(task.id(), workerId, documentId, "DELETED", null, "DELETING");
            } else {
                IngestProcessor.Outcome outcome = processor.index(task, doc, workerId);
                pipeline.succeed(task.id(), workerId, documentId, "READY", outcome.engineDocId(),
                        "INDEXING", "QUEUED", "PARSING");
            }
            ledger.updateConsumptionOutcome(eventId, "SUCCEEDED", Instant.now());
            ack.acknowledge();
        } catch (ApiException e) {
            pipeline.fail(task.id(), workerId, operationId, documentId, version, tenantId, correlationId,
                    e.code(), e.getMessage(), origin(record), deleteOperation);
            ledger.updateConsumptionOutcome(eventId, "FAILED_" + e.code().name(), Instant.now());
            ack.acknowledge();
        } catch (RuntimeException e) {
            log.error("ingest_unexpected doc={} err={}", documentId, e.toString());
            pipeline.fail(task.id(), workerId, operationId, documentId, version, tenantId, correlationId,
                    ErrorCode.INTERNAL_ERROR, e.getClass().getSimpleName(), origin(record), deleteOperation);
            ledger.updateConsumptionOutcome(eventId, "FAILED_INTERNAL", Instant.now());
            ack.acknowledge();
        }
    }

    private PipelineService.Origin origin(ConsumerRecord<String, String> record) {
        return new PipelineService.Origin(record.topic(), record.partition(), record.offset(), record.value());
    }
}
