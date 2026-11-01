package com.acme.kb.p1.audit;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.repo.AuditMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 审计门面。P1 的口径是“分层的强一致”：
 * - 有业务事务的写操作：{@link #recordInTx}，与业务同事务，失败回滚并返回 503。
 * - 无业务事务的动作（登录/登出）：{@link #recordIndependent}，独立事务，失败 fail-close。
 * - 安全敏感读（问答、引用预览、下载、审计查询）：{@link #recordIndependent}，失败不返回内容。
 * - 普通元数据读：{@link #recordBestEffort}，失败只计数告警，不阻断。
 * 任何路径都不会把“业务成功、审计失败”当成正常状态。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditWriter writer;
    private final AuditMapper audit;

    public final LongAdder written = new LongAdder();
    public final LongAdder failures = new LongAdder();
    public final LongAdder bestEffortFailures = new LongAdder();

    public AuditService(AuditWriter writer, AuditMapper audit) {
        this.writer = writer;
        this.audit = audit;
    }

    /** 业务事务内调用：没有事务会直接失败，避免悄悄退化成旁路写。 */
    public void recordInTx(Actor actor, String action, String resourceType, String resourceId,
                           String result, String reasonCode, Map<String, Object> metadata) {
        AuditWriter.AuditFields f = fields(actor, action, resourceType, resourceId, result, reasonCode,
                metadata);
        try {
            writer.inBusinessTx(f);
            written.increment();
        } catch (RuntimeException e) {
            failures.increment();
            log.error("audit_in_tx_failed action={} resource={}/{} code={}", action, resourceType,
                    resourceId, reasonCode, e);
            throw new ApiException(ErrorCode.AUDIT_WRITE_FAILED, "审计写入失败，操作已取消");
        }
    }

    /** 独立事务：登录/登出与安全敏感读使用；失败必须让调用方拒绝返回内容。 */
    public void recordIndependent(Actor actor, String action, String resourceType, String resourceId,
                                  String result, String reasonCode, Map<String, Object> metadata) {
        AuditWriter.AuditFields f = fields(actor, action, resourceType, resourceId, result, reasonCode,
                metadata);
        try {
            writer.independent(f);
            written.increment();
        } catch (RuntimeException e) {
            failures.increment();
            log.error("audit_independent_failed action={} resource={}/{} code={}", action, resourceType,
                    resourceId, reasonCode, e);
            throw new ApiException(ErrorCode.AUDIT_WRITE_FAILED, "审计不可用，操作已拒绝");
        }
    }

    public void recordBestEffort(Actor actor, String action, String resourceType, String resourceId,
                                 String result, String reasonCode, Map<String, Object> metadata) {
        try {
            writer.independent(fields(actor, action, resourceType, resourceId, result, reasonCode, metadata));
            written.increment();
        } catch (RuntimeException e) {
            bestEffortFailures.increment();
            log.warn("audit_best_effort_failed action={} resource={}/{} code={} err={}", action,
                    resourceType, resourceId, reasonCode, e.getClass().getSimpleName());
        }
    }

    /** 兼容 P0 调用点：普通 allow/deny 走尽力而为；安全敏感路径必须显式用 Strict 版本。 */
    public void record(Actor actor, String action, String resourceType, String resourceId,
                       String result, String reasonCode, Map<String, Object> metadata) {
        recordBestEffort(actor, action, resourceType, resourceId, result, reasonCode, metadata);
    }

    public void allow(Actor actor, String action, String resourceType, String resourceId) {
        recordBestEffort(actor, action, resourceType, resourceId, "ALLOW", null, Map.of());
    }

    public void deny(Actor actor, String action, String resourceType, String resourceId, String code) {
        recordBestEffort(actor, action, resourceType, resourceId, "DENY", code, Map.of());
    }

    public void allowStrict(Actor actor, String action, String resourceType, String resourceId) {
        recordIndependent(actor, action, resourceType, resourceId, "ALLOW", null, Map.of());
    }

    public void denyStrict(Actor actor, String action, String resourceType, String resourceId, String code) {
        recordIndependent(actor, action, resourceType, resourceId, "DENY", code, Map.of());
    }

    /** 链校验：逐条重算 record_hash 并核对 prev_hash 衔接；发现断链返回 false。 */
    public boolean verifyChain(String tenantId) {
        List<AuditMapper.Row> rows = audit.chain(tenantId);
        String prev = null;
        long expected = 1;
        for (AuditMapper.Row row : rows) {
            if (row.seq() != expected) {
                return false;
            }
            String canonical = String.join("|", nz(row.tenantId()), nz(row.actorId()), row.action(),
                    row.resourceType(), nz(row.resourceId()), row.result(), nz(row.reasonCode()),
                    nz(row.requestId()), nz(row.traceId()), nz(row.sourceIp()), nz(row.metadataJson()),
                    String.valueOf(row.createdAt().toEpochMilli()), nz(prev));
            String recomputed = Hashing.sha256Hex(canonical);
            if (!recomputed.equals(row.recordHash()) || !nz(prev).equals(nz(row.prevHash()))) {
                return false;
            }
            prev = row.recordHash();
            expected++;
        }
        return true;
    }

    private static AuditWriter.AuditFields fields(Actor actor, String action, String resourceType,
                                                  String resourceId, String result, String reasonCode,
                                                  Map<String, Object> metadata) {
        AuditWriter.AuditFields base = AuditWriter.AuditFields.of(actor, action, resourceType, resourceId,
                result, reasonCode, metadata);
        return new AuditWriter.AuditFields(base.tenantId(), base.actorId(), base.action(),
                base.resourceType(), base.resourceId(), base.result(), base.reasonCode(), base.requestId(),
                base.traceId(), sourceIp(), base.metadata());
    }

    private static String sourceIp() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servlet) {
                HttpServletRequest request = servlet.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    return forwarded.split(",")[0].strip();
                }
                return request.getRemoteAddr();
            }
        } catch (RuntimeException ignored) {
            // 非请求线程（worker/定时任务）没有来源 IP
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
