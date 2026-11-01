package com.acme.kb.p1.config;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 就绪探针。Boot 3.5 不再自带 kafka 健康指示器，而"队列能不能收事件"是入库链路的前提，
 * 因此这里显式建一次，readiness 才有意义。探针只回答可达性与主题齐备，不代表积压已清空。
 */
@Configuration(proxyBeanMethods = false)
public class HealthProbes {

    private static final Logger log = LoggerFactory.getLogger(HealthProbes.class);

    /** 复用同一个 AdminClient：断连后它会自行重连，探针每次只发起一次元数据请求。 */
    @Bean(destroyMethod = "close")
    public Admin kafkaAdminClient(KafkaAdmin admin) {
        Map<String, Object> props = new HashMap<>(admin.getConfigurationProperties());
        props.putIfAbsent(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.putIfAbsent(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        return Admin.create(props);
    }

    @Bean
    public HealthIndicator kafkaProbe(Admin kafkaAdminClient, KafkaProperties kafka,
                                      KbProperties props) {
        List<String> required = List.of(props.ingest().topicRequested(), props.ingest().topicRetry(),
                props.ingest().topicDlq(), props.ingest().topicDelete());
        return () -> {
            Set<String> existing;
            try {
                existing = kafkaAdminClient.listTopics().names().get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Health.down().withDetail("detail", "probe interrupted").build();
            } catch (Exception e) {
                log.warn("kafka_probe_unreachable err={}", e.getClass().getSimpleName());
                return Health.down().withDetail("bootstrap", String.join(",", kafka.getBootstrapServers()))
                        .withDetail("detail", "broker unreachable").build();
            }
            List<String> missing = new ArrayList<>();
            for (String topic : required) {
                if (!existing.contains(topic)) {
                    missing.add(topic);
                }
            }
            if (!missing.isEmpty()) {
                return Health.down().withDetail("bootstrap", String.join(",", kafka.getBootstrapServers()))
                        .withDetail("detail", "missing topics: " + String.join(" ", missing)).build();
            }
            return Health.up().withDetail("bootstrap", String.join(",", kafka.getBootstrapServers())).build();
        };
    }
}
