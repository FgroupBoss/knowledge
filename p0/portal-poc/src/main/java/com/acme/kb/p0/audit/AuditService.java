package com.acme.kb.p0.audit;

import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.common.TraceIds;
import com.acme.kb.p0.repo.AuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 审计摘要：只记主体/动作/资源/结果/原因/追踪，绝不写入正文、Prompt、凭据或对象访问链接。
 * 写入失败记 ERROR 但不改变业务判定（P1 需改为事务化 outbox 投递，见 ADR-007）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditMapper audit;
    private final ObjectMapper mapper;

    public AuditService(AuditMapper audit, ObjectMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    public void record(Actor actor, String action, String resourceType, String resourceId,
                       String result, String reasonCode, Map<String, Object> metadata) {
        try {
            audit.insert(UUID.randomUUID().toString(),
                    actor == null ? null : actor.tenantId(),
                    actor == null ? null : actor.userId(),
                    action, resourceType, resourceId, result, reasonCode,
                    TraceIds.current(), TraceIds.current(), null,
                    metadata == null || metadata.isEmpty() ? null : mapper.writeValueAsString(metadata),
                    Instant.now());
        } catch (Exception e) {
            log.error("audit write failed action={} resource={}/{} result={} code={}",
                    action, resourceType, resourceId, result, reasonCode, e);
        }
    }

    public void allow(Actor actor, String action, String resourceType, String resourceId) {
        record(actor, action, resourceType, resourceId, "ALLOW", null, Map.of());
    }

    public void deny(Actor actor, String action, String resourceType, String resourceId, String code) {
        record(actor, action, resourceType, resourceId, "DENY", code, Map.of());
    }
}
