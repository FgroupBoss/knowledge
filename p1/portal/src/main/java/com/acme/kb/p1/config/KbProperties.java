package com.acme.kb.p1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * P1 全部可调参数。每项都在 contracts/config-catalog.md 中有对应说明：
 * 环境变量 → 属性 → 代码默认值三层同名对齐。
 */
@ConfigurationProperties(prefix = "kb")
public record KbProperties(
        @DefaultValue("api") String role,
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
        @DefaultValue Session session,
        @DefaultValue Security security,
        @DefaultValue Audit audit,
        @DefaultValue Database database,
        @DefaultValue Wiki wiki) {

    public boolean isApi() {
        return "api".equalsIgnoreCase(role);
    }

    public boolean isWorker() {
        return "worker".equalsIgnoreCase(role);
    }

    public record Upload(
            @DefaultValue("104857600") long maxBytes,
            @DefaultValue("3") int maxConcurrent,
            List<String> allowedExtensions,
            List<String> allowedMimeTypes) {
    }

    public record Storage(
            @DefaultValue("kb-p1-originals-private") String bucket,
            @DefaultValue("http://127.0.0.1:19000") String endpoint,
            String accessKey,
            String accessKeyFile,
            String secretKey,
            String secretKeyFile,
            @DefaultValue("30") int retentionDays) {
    }

    public record Ingest(
            @DefaultValue("kb.ingest.requested.v1") String topicRequested,
            @DefaultValue("kb.ingest.retry.v1") String topicRetry,
            @DefaultValue("kb.ingest.dlq.v1") String topicDlq,
            @DefaultValue("kb.document.delete.v1") String topicDelete,
            @DefaultValue("kb.ingest.reconcile.v1") String topicReconcile,
            @DefaultValue("p1-ingest") String consumerGroup,
            @DefaultValue("p1-delete") String deleteConsumerGroup,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("2000") long backoffBaseMs,
            @DefaultValue("300000") long backoffMaxMs,
            @DefaultValue("45") int leaseSeconds,
            @DefaultValue("15") int heartbeatSeconds,
            @DefaultValue("500") long retryPumpMs,
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
            @DefaultValue("true") boolean capabilityProbe,
            @DefaultValue("ollama") String embeddingMode,
            @DefaultValue("ollama") String chatMode,
            @DefaultValue("256") int fallbackEmbeddingDim,
            String token,
            String tokenFile,
            @DefaultValue("/api/v1/knowledge-bases") String weknoraKbPath,
            @DefaultValue("/api/v1/search") String weknoraSearchPath) {
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
            @DefaultValue("0.1") double temperature,
            @DefaultValue("true") boolean digestVerify) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60") long ttlSeconds,
            @DefaultValue("300") long versionTtlSeconds,
            @DefaultValue("p1") String namespace) {
    }

    public record Chat(
            @DefaultValue("30") int ratePerMinute,
            @DefaultValue("300") int tenantPerMinute,
            @DefaultValue("calibration.json") String calibrationFile) {
    }

    public record Auth(
            /**
             * 仅本地开发/自动化测试可使用测试身份头。pilot 镜像必须为 false；
             * 该开关为 true 时启动会打印显式告警，且拒绝与 offline-enforced 同时开启。
             */
            @DefaultValue("false") boolean devSubjectEnabled) {
    }

    public record Session(
            @DefaultValue("__Host-kb_session") String cookieName,
            @DefaultValue("8h") String timeout,
            @DefaultValue("12h") String absoluteTimeout,
            @DefaultValue("XSRF-TOKEN") String csrfCookie,
            @DefaultValue("X-XSRF-TOKEN") String csrfHeader) {
    }

    public record Security(
            String oidcIssuer,
            String oidcClientId,
            String oidcClientSecret,
            String oidcClientSecretFile,
            String oidcRedirectUri,
            @DefaultValue("openid,profile,email") String oidcScopes,
            @DefaultValue("false") boolean jitEnabled) {
    }

    public record Audit(
            @DefaultValue("180") int retentionDays,
            @DefaultValue("true") boolean chainEnabled,
            @DefaultValue("kb.audit.v1") String topic) {
    }

    /**
     * #6 账号拆分：Flyway 用 DDL 账号（spring.flyway.user），运行账号只有 DML。
     * 启动时校验运行账号没有 DDL/GRANT 权限；不满足则拒绝启动。
     */
    public record Database(@DefaultValue("true") boolean enforceLeastPrivilege) {
    }

    /**
     * P2 Wiki 治理。默认关闭：未验收的功能不进产物（AGENTS.md §3），
     * enabled=false 时不注册 Wiki 路由与调度器。
     * sync-concurrency 与 kb.ollama.max-concurrent 分账（D-12），发布不挤占在线问答。
     */
    public record Wiki(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("204800") int maxBodyBytes,
            @DefaultValue("2") int syncConcurrency,
            @DefaultValue("5000") long backoffBaseMs,
            @DefaultValue("600000") long backoffMaxMs,
            @DefaultValue("6") int maxAttempts,
            @DefaultValue("45") int leaseSeconds,
            @DefaultValue("15") int heartbeatSeconds,
            @DefaultValue("14") int staleDays,
            @DefaultValue("0") int expireAfterDays,
            @DefaultValue("72") int reviewSlaHours,
            @DefaultValue("0 30 2 * * *") String reminderCron,
            @DefaultValue("180") int retentionDays,
            @DefaultValue("kb.wiki.sync.v1") String topicSync,
            @DefaultValue("kb.wiki.retract.v1") String topicRetract,
            @DefaultValue("kb.wiki.dlq.v1") String topicDlq,
            @DefaultValue("p1-wiki-sync") String consumerGroup,
            @DefaultValue("p1-wiki-retract") String retractConsumerGroup) {
    }
}
