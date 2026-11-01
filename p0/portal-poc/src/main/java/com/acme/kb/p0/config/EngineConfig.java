package com.acme.kb.p0.config;

import com.acme.kb.p0.engine.ChatModelClient;
import com.acme.kb.p0.engine.DeterministicEmbeddingProvider;
import com.acme.kb.p0.engine.EmbeddingProvider;
import com.acme.kb.p0.engine.ExtractiveChatModelClient;
import com.acme.kb.p0.engine.KnowledgeEnginePort;
import com.acme.kb.p0.engine.OllamaChatModelClient;
import com.acme.kb.p0.engine.OllamaEmbeddingProvider;
import com.acme.kb.p0.engine.PendingWeKnoraAdapter;
import com.acme.kb.p0.engine.StubKnowledgeEngine;
import com.acme.kb.p0.engine.parsing.DocumentParser;
import com.acme.kb.p0.repo.EngineStoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/** 模型与引擎装配：provider / embedding / chat 三条选择集中在此，业务代码不做分支。 */
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    public HttpClient p0HttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public EmbeddingProvider embeddingProvider(P0Properties props, HttpClient http, ObjectMapper mapper) {
        assertNoCloudModel(props, props.ollama().baseUrl());
        if ("deterministic".equalsIgnoreCase(props.engine().embeddingMode())) {
            log.warn("embedding mode=deterministic：向量非模型产出，检索质量指标不可作为结论");
            return new DeterministicEmbeddingProvider(props.engine().fallbackEmbeddingDim());
        }
        return new OllamaEmbeddingProvider(http, mapper, props.ollama().baseUrl(),
                props.ollama().embedModel(), props.ollama().timeoutMs(), props.ollama().maxConcurrent());
    }

    @Bean
    public ChatModelClient chatModelClient(P0Properties props, HttpClient http, ObjectMapper mapper) {
        assertNoCloudModel(props, props.ollama().baseUrl());
        if ("extractive".equalsIgnoreCase(props.engine().chatMode())) {
            return new ExtractiveChatModelClient();
        }
        return new OllamaChatModelClient(http, mapper, props.ollama().baseUrl(), props.ollama().chatModel(),
                props.ollama().timeoutMs(), props.ollama().maxConcurrent(),
                props.ollama().maxNewTokens(), props.ollama().temperature());
    }

    @Bean
    public KnowledgeEnginePort knowledgeEnginePort(P0Properties props, EngineStoreMapper store,
                                                   DocumentParser parser, EmbeddingProvider embeddings,
                                                   ObjectMapper mapper) {
        String provider = props.engine().provider() == null ? "stub"
                : props.engine().provider().toLowerCase(Locale.ROOT);
        switch (provider) {
            case "weknora" -> {
                return new PendingWeKnoraAdapter();
            }
            case "stub" -> {
                return new StubKnowledgeEngine(store, parser, embeddings, mapper, props.engine());
            }
            default -> throw new IllegalStateException("未知 p0.engine.provider: " + provider);
        }
    }

    /** bean 名即健康检查指示器名，readiness group 里引用 engineProbe。 */
    @Bean
    public HealthIndicator engineProbe(KnowledgeEnginePort port) {
        return () -> {
            KnowledgeEnginePort.Health h = port.health();
            Health.Builder builder = h.available() ? Health.up() : Health.down();
            return builder.withDetail("provider", h.provider())
                    .withDetail("detail", h.detail() == null ? "" : h.detail())
                    .build();
        };
    }

    /** 断网模式下推理地址必须是回环或私网，杜绝静默回退公网模型。 */
    private static void assertNoCloudModel(P0Properties props, String baseUrl) {
        if (!props.offlineEnforced()) {
            return;
        }
        String host = URI.create(baseUrl).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("p0.ollama.base-url 缺少主机名");
        }
        boolean ipLiteral = host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        if (ipLiteral) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    return;
                }
            } catch (UnknownHostException e) {
                throw new IllegalStateException("p0.ollama.base-url 主机无法解析: " + host);
            }
            throw new IllegalStateException("离线模式禁止使用公网 IP 作为推理地址: " + host);
        }
        log.info("离线模式：推理主机名为 {}，由 internal 网络保证不出网", host);
    }
}
