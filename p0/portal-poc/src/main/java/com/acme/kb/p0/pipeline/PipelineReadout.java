package com.acme.kb.p0.pipeline;

import com.acme.kb.p0.api.Dto;
import com.acme.kb.p0.authz.AclCacheService;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.EngineStoreMapper;
import com.acme.kb.p0.repo.PipelineCommandMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管道取证读数：积压、任务分布、Kafka lag、DLQ 与缓存命中全部来自 MySQL/Kafka 实况，
 * 只返回计数与状态，不返回正文、对象键或事件载荷。
 */
@Service
public class PipelineReadout {

    private static final Logger log = LoggerFactory.getLogger(PipelineReadout.class);
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(4);

    private final PipelineCommandMapper pipeline;
    private final DocumentMapper documents;
    private final EngineStoreMapper engineStore;
    private final AclCacheService cache;
    private final P0Properties props;
    private final KafkaProperties kafka;

    public PipelineReadout(PipelineCommandMapper pipeline, DocumentMapper documents,
                           EngineStoreMapper engineStore, AclCacheService cache, P0Properties props,
                           KafkaProperties kafka) {
        this.pipeline = pipeline;
        this.documents = documents;
        this.engineStore = engineStore;
        this.cache = cache;
        this.props = props;
        this.kafka = kafka;
    }

    public Dto.Backlog backlog() {
        Map<String, Integer> byStatus = taskStatusCounts();
        AclCacheService.CacheStats stats = cache.stats();
        Map<String, Long> cacheStats = new LinkedHashMap<>();
        cacheStats.put("hits", stats.hits());
        cacheStats.put("misses", stats.misses());
        cacheStats.put("staleRejected", stats.staleRejected());
        cacheStats.put("redisErrors", stats.redisErrors());
        cacheStats.put("invalidations", stats.invalidations());
        return new Dto.Backlog(pipeline.unpublishedCount(), pipeline.oldestUnpublishedAgeSeconds(),
                byStatus, kafkaLag(props.ingest().consumerGroup(),
                        List.of(props.ingest().topicRequested(), props.ingest().topicRetry())),
                kafkaLag(props.ingest().deleteConsumerGroup(), List.of(props.ingest().topicDelete())),
                byStatus.getOrDefault("DEAD_LETTERED", 0),
                documents.countUnconverged(), engineStore.countChunks(),
                engineStore.countSearchableDocuments(), cacheStats);
    }

    private Map<String, Integer> taskStatusCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> row : pipeline.taskStatusCounts()) {
            Object status = row.get("status");
            Object total = row.get("total");
            if (status != null) {
                out.put(String.valueOf(status), total == null ? 0 : ((Number) total).intValue());
            }
        }
        return out;
    }

    /**
     * lag = Σ(logEndOffset − committedOffset)，按消费组分别统计：
     * 入库组与删除组是不同 group，把它们混成一个数字会掩盖真实积压。
     * 临时建一个同组消费者只读位点，用 assign（而非 subscribe）避免把在线消费组拉进再均衡。
     */
    private Dto.KafkaLag kafkaLag(String group, List<String> topics) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(group))) {
            Set<TopicPartition> partitions = new LinkedHashSet<>();
            for (String topic : topics) {
                for (PartitionInfo info : consumer.partitionsFor(topic, ADMIN_TIMEOUT)) {
                    partitions.add(new TopicPartition(topic, info.partition()));
                }
            }
            if (partitions.isEmpty()) {
                return new Dto.KafkaLag(group, 0, 0, 0, 0);
            }
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(partitions, ADMIN_TIMEOUT);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, ADMIN_TIMEOUT);
            long current = 0;
            long end = 0;
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata meta = committed.get(tp);
                current += meta == null ? 0 : meta.offset();
                end += ends.getOrDefault(tp, 0L);
            }
            return new Dto.KafkaLag(group, partitions.size(), current, end, Math.max(0, end - current));
        } catch (org.apache.kafka.common.errors.TimeoutException e) {
            log.warn("kafka_lag_timeout");
            return unavailableLag(group);
        } catch (Exception e) {
            log.warn("kafka_lag_read_failed err={}", e.getClass().getSimpleName());
            return unavailableLag(group);
        }
    }

    private Map<String, Object> consumerProps(String group) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
        return config;
    }

    private Dto.KafkaLag unavailableLag(String group) {
        return new Dto.KafkaLag(group, -1, -1, -1, -1);
    }
}
