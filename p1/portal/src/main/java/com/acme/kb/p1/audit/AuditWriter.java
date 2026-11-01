package com.acme.kb.p1.audit;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.AuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审计的真实写入点，按事务语义分成两种，绝不混用：
 * - {@link #inBusinessTx}：MANDATORY，必须挂在业务事务里；审计失败则业务一起回滚。
 * - {@link #independent}：REQUIRES_NEW，用于登录/登出/安全敏感读；失败可单独回滚并 fail-close。
 *
 * 同一租户的哈希链通过 audit_head 行锁串行化；不同租户互不阻塞。
 */
@Component
public class AuditWriter {

    private final AuditMapper audit;
    private final ObjectMapper mapper;
    private final boolean chainEnabled;
    private final String auditTopic;

    public AuditWriter(AuditMapper audit, ObjectMapper mapper, KbProperties props) {
        this.audit = audit;
        this.mapper = mapper;
        this.chainEnabled = props.audit().chainEnabled();
        this.auditTopic = props.audit().topic();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void inBusinessTx(AuditFields f) {
        insert(f);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void independent(AuditFields f) {
        insert(f);
    }

    private void insert(AuditFields f) {
        // MySQL DATETIME(3) 会对第 4 位小数四舍五入，而哈希用的是 toEpochMilli()。
        // 不先截断会出现“哈希按 .225 算、库里存 .226”的偶发校验失败。
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String metadataJson = toJson(f.metadata());
        Long seq = null;
        String prevHash = null;
        String recordHash = null;
        if (chainEnabled && f.tenantId() != null) {
            audit.ensureHead(f.tenantId(), now);
            Map<String, Object> head = audit.lockHead(f.tenantId());
            long lastSeq = head == null ? 0 : ((Number) head.getOrDefault("lastSeq", 0)).longValue();
            prevHash = head == null ? null : (String) head.get("lastHash");
            seq = lastSeq + 1;
            recordHash = hashOf(f, metadataJson, now, prevHash);
            audit.updateHead(f.tenantId(), seq, recordHash, now);
        }
        String auditId = UUID.randomUUID().toString();
        audit.insert(auditId, seq, f.tenantId(), f.actorId(), f.action(),
                f.resourceType(), f.resourceId(), f.result(), f.reasonCode(), f.requestId(), f.traceId(),
                f.sourceIp(), metadataJson, now, prevHash, recordHash);
        enqueueExport(auditId, f, metadataJson, now, seq, recordHash);
    }

    /**
     * 审计外发走事务内 outbox：与审计行同生共死，Kafka 不可用不影响在线审计。
     * 事件体只含字段集合，不含正文/Prompt/凭据。
     */
    private void enqueueExport(String auditId, AuditFields f, String metadataJson, Instant now,
                               Long seq, String recordHash) {
        if (f.tenantId() == null) {
            // 未识别主体的登录失败事件没有租户，不进外发通道（避免跨租户分区不明）
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditId", auditId);
        payload.put("tenantId", f.tenantId());
        payload.put("actorId", f.actorId());
        payload.put("action", f.action());
        payload.put("resourceType", f.resourceType());
        payload.put("resourceId", f.resourceId());
        payload.put("result", f.result());
        payload.put("reasonCode", f.reasonCode());
        payload.put("traceId", f.traceId());
        payload.put("seq", seq);
        payload.put("recordHash", recordHash);
        payload.put("createdAt", now.toString());
        try {
            audit.insertOutbox(UUID.randomUUID().toString(), f.tenantId(), UUID.randomUUID().toString(),
                    auditId, auditTopic, f.tenantId(), mapper.writeValueAsString(payload), now);
        } catch (Exception e) {
            throw new IllegalStateException("audit outbox serialization failed", e);
        }
    }

    private static String hashOf(AuditFields f, String metadataJson, Instant now, String prevHash) {
        String canonical = String.join("|",
                nz(f.tenantId()), nz(f.actorId()), f.action(), f.resourceType(), nz(f.resourceId()),
                f.result(), nz(f.reasonCode()), nz(f.requestId()), nz(f.traceId()), nz(f.sourceIp()),
                nz(metadataJson), String.valueOf(now.toEpochMilli()), nz(prevHash));
        return Hashing.sha256Hex(canonical);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            // TreeMap 保证同一逻辑内容的序列化顺序稳定，便于链校验重现
            return mapper.writeValueAsString(new java.util.TreeMap<>(metadata));
        } catch (Exception e) {
            throw new IllegalStateException("audit metadata serialization failed", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 审计字段的最小集合：不含正文、Prompt、对象键、凭据。 */
    public record AuditFields(String tenantId, String actorId, String action, String resourceType,
                              String resourceId, String result, String reasonCode, String requestId,
                              String traceId, String sourceIp, Map<String, Object> metadata) {

        public static AuditFields of(Actor actor, String action, String resourceType, String resourceId,
                                     String result, String reasonCode, Map<String, Object> metadata) {
            return new AuditFields(actor == null ? null : actor.tenantId(),
                    actor == null ? null : actor.userId(), action, resourceType, resourceId, result,
                    reasonCode, TraceIds.current(), TraceIds.current(), null,
                    metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
        }
    }
}
