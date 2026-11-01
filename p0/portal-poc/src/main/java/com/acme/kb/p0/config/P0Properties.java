package com.acme.kb.p0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** 全部 P0 可调参数集中在契约文档 contracts/config-catalog.md 中说明。 */
@ConfigurationProperties(prefix = "p0")
public record P0Properties(
        @DefaultValue("../compose/secrets") String secretsDir,
        @DefaultValue("false") boolean offlineEnforced,
        @DefaultValue("../../.tmp") String tempDir,
        @DefaultValue Upload upload,
        @DefaultValue Storage storage,
        @DefaultValue Ingest ingest,
        @DefaultValue Engine engine,
        @DefaultValue Ollama ollama,
        @DefaultValue Cache cache,
        @DefaultValue Chat chat,
        @DefaultValue Auth auth,
        @DefaultValue Fixture fixture) {

    public record Upload(
            @DefaultValue("104857600") long maxBytes,
            @DefaultValue("3") int maxConcurrent,
            List<String> allowedExtensions,
            List<String> allowedMimeTypes) {
    }

    public record Storage(
            @DefaultValue("kb-p0-originals-private") String bucket,
            @DefaultValue("http://127.0.0.1:19000") String endpoint,
            String accessKey,
            String accessKeyFile,
            String secretKey,
            String secretKeyFile,
            @DefaultValue("7") int retentionDays) {
    }

    public record Ingest(
            @DefaultValue("kb.ingest.requested.v1") String topicRequested,
            @DefaultValue("kb.ingest.retry.v1") String topicRetry,
            @DefaultValue("kb.ingest.dlq.v1") String topicDlq,
            @DefaultValue("kb.document.delete.v1") String topicDelete,
            @DefaultValue("p0-ingest") String consumerGroup,
            @DefaultValue("p0-delete") String deleteConsumerGroup,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("2000") long backoffBaseMs,
            @DefaultValue("45") int leaseSeconds,
            @DefaultValue("50") int outboxBatch,
            @DefaultValue("500") long outboxIntervalMs) {
    }

    public record Engine(
            @DefaultValue("stub") String provider,
            @DefaultValue("http://127.0.0.1:18180/__engine-stub__") String baseUrl,
            @DefaultValue("60000") long timeoutMs,
            @DefaultValue("800") int chunkSize,
            @DefaultValue("120") int chunkOverlap,
            @DefaultValue("20") int topK,
            @DefaultValue("6") int contextChunks,
            @DefaultValue("0.18") double scoreThreshold,
            @DefaultValue("false") boolean leakOutOfScope,
            @DefaultValue("ollama") String embeddingMode,
            @DefaultValue("ollama") String chatMode,
            @DefaultValue("256") int fallbackEmbeddingDim,
            String token) {
    }

    public record Ollama(
            @DefaultValue("http://127.0.0.1:11434") String baseUrl,
            @DefaultValue("deepseek-r1:8b") String chatModel,
            @DefaultValue("nomic-embed-text") String embedModel,
            @DefaultValue("true") boolean allowRemote,
            @DefaultValue("180000") long timeoutMs,
            @DefaultValue("5") int maxConcurrent,
            @DefaultValue("4096") int maxPromptChars,
            @DefaultValue("800") int maxNewTokens,
            @DefaultValue("0.1") double temperature) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60") long ttlSeconds,
            @DefaultValue("p0") String namespace) {
    }

    public record Chat(
            @DefaultValue("30") int ratePerMinute) {
    }

    public record Auth(
            @DefaultValue("true") boolean stubEnabled) {
    }

    /** 验证夹具：只用于构造租户/成员/ACL 样本与取证，P1 不存在这一层。 */
    public record Fixture(
            @DefaultValue("false") boolean enabled,
            String token,
            String tokenFile,
            @DefaultValue("red") String baselineTenant,
            @DefaultValue("admin") String baselineAdmin) {
    }
}
