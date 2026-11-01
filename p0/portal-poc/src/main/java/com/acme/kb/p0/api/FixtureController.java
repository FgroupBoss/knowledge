package com.acme.kb.p0.api;

import com.acme.kb.p0.audit.AuditService;
import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.auth.ActorHolder;
import com.acme.kb.p0.auth.WorkspaceRole;
import com.acme.kb.p0.authz.AclCacheService;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.Classification;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.pipeline.PipelineReadout;
import com.acme.kb.p0.scope.FixtureService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证夹具端点：构造租户/成员/密级/文档 ACL 样本并读取管道积压。
 * 只有 p0.fixture.enabled=true 且带独立口令时才可用；生产形态下这些路由根本不注册。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "p0.fixture", name = "enabled", havingValue = "true")
public class FixtureController {

    private final FixtureService fixture;
    private final PipelineReadout readout;
    private final AclCacheService cache;
    private final AuditService audit;
    private final FixtureGuard guard;

    public FixtureController(FixtureService fixture, PipelineReadout readout, AclCacheService cache,
                             AuditService audit, FixtureGuard guard) {
        this.fixture = fixture;
        this.readout = readout;
        this.cache = cache;
        this.audit = audit;
        this.guard = guard;
    }

    public record CodeName(String code, String name) {
    }

    public record CreateKnowledgeBase(String code, String name, String classification) {
    }

    public record MemberRequest(String userCode, String role) {
    }

    public record DocumentAclRequest(String userCode, String grant) {
    }

    public record PrincipalRequest(String tenantCode, String userCode, String displayName, String clearance) {
    }

    public record PoisonRequest(String kbId, String userCode, long aclVersion, String role) {
    }

    @GetMapping("/me")
    public Dto.Principal me() {
        return fixture.whoami(ActorHolder.require());
    }

    @PostMapping("/workspaces")
    public ResponseEntity<Dto.WorkspaceView> createWorkspace(@RequestBody CodeName body,
                                                             HttpServletRequest request) {
        guard.require(request);
        Actor actor = ActorHolder.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fixture.createWorkspace(actor, body.code(), body.name()));
    }

    @PostMapping("/workspaces/{workspaceId}/knowledge-bases")
    public ResponseEntity<Dto.KnowledgeBaseView> createKnowledgeBase(@PathVariable String workspaceId,
                                                                    @RequestBody CreateKnowledgeBase body,
                                                                    HttpServletRequest request) {
        guard.require(request);
        Actor actor = ActorHolder.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(fixture.createKnowledgeBase(actor, workspaceId,
                body.code(), body.name(), body.classification()));
    }

    @PostMapping("/workspaces/{workspaceId}/members")
    public ResponseEntity<Map<String, Object>> addMember(@PathVariable String workspaceId,
                                                        @RequestBody MemberRequest body,
                                                        HttpServletRequest request) {
        guard.require(request);
        fixture.addMember(ActorHolder.require(), workspaceId, body.userCode(), body.role());
        return ResponseEntity.ok(Map.of("workspaceId", workspaceId, "userCode", body.userCode(),
                "role", body.role() == null ? "" : body.role().toUpperCase(), "applied", true));
    }

    @DeleteMapping("/workspaces/{workspaceId}/members")
    public ResponseEntity<Map<String, Object>> removeMember(@PathVariable String workspaceId,
                                                           @RequestParam String userCode,
                                                           HttpServletRequest request) {
        guard.require(request);
        fixture.removeMember(ActorHolder.require(), workspaceId, userCode);
        return ResponseEntity.ok(Map.of("workspaceId", workspaceId, "userCode", userCode, "revoked", true));
    }

    @PostMapping("/documents/{documentId}/acl")
    public ResponseEntity<Map<String, Object>> grantDocumentAcl(@PathVariable String documentId,
                                                               @RequestBody DocumentAclRequest body,
                                                               HttpServletRequest request) {
        guard.require(request);
        fixture.grantDocumentAcl(ActorHolder.require(), documentId, body.userCode(), body.grant());
        return ResponseEntity.ok(Map.of("documentId", documentId, "userCode", body.userCode(),
                "grant", body.grant() == null ? "" : body.grant().toUpperCase(), "applied", true));
    }

    @PostMapping("/fixture/tenants")
    public ResponseEntity<Map<String, Object>> ensureTenant(@RequestBody CodeName body,
                                                           HttpServletRequest request) {
        guard.require(request);
        fixture.ensureTenant(body.code(), body.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("tenantCode", body.code()));
    }

    @PostMapping("/fixture/principals")
    public ResponseEntity<Map<String, Object>> ensurePrincipal(@RequestBody PrincipalRequest body,
                                                              HttpServletRequest request) {
        guard.require(request);
        fixture.ensurePrincipal(body.tenantCode(), body.userCode(), body.displayName(), body.clearance());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "tenantCode", body.tenantCode(),
                "userCode", body.userCode(),
                "clearance", Classification.parse(body.clearance()).name()));
    }

    /** 取证：积压、任务分布、Kafka lag、DLQ 与缓存统计，只有计数没有正文。 */
    @GetMapping("/verification/backlog")
    public Dto.Backlog backlog(HttpServletRequest request) {
        guard.require(request);
        requireAdmin(ActorHolder.require());
        return readout.backlog();
    }

    @GetMapping("/verification/cache-stats")
    public Map<String, Object> cacheStats(HttpServletRequest request) {
        guard.require(request);
        requireAdmin(ActorHolder.require());
        AclCacheService.CacheStats stats = cache.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hits", stats.hits());
        out.put("misses", stats.misses());
        out.put("staleRejected", stats.staleRejected());
        out.put("redisErrors", stats.redisErrors());
        out.put("invalidations", stats.invalidations());
        return out;
    }

    /**
     * 攻击夹具：把一条"旧权限"决策写进缓存，用于证明水位比较会拒绝它。
     * 只在验证环境可用，且必须显式指定一个低于当前水位的 aclVersion。
     */
    @PostMapping("/verification/cache/poison")
    public Map<String, Object> poison(@RequestBody PoisonRequest body, HttpServletRequest request) {
        guard.require(request);
        Actor actor = ActorHolder.require();
        requireAdmin(actor);
        String userId = fixture.userIdOf(actor.tenantId(), body.userCode());
        cache.poisonWithStaleDecision(body.kbId(), userId, body.aclVersion(),
                body.role() == null ? WorkspaceRole.OWNER.name() : body.role().toUpperCase());
        audit.record(actor, "verification.cache.poison", "knowledge_base", body.kbId, "ALLOW", null,
                Map.of("staleAclVersion", body.aclVersion()));
        return Map.of("kbId", body.kbId(), "userCode", body.userCode(),
                "staleAclVersion", body.aclVersion(), "poisoned", true);
    }

    private void requireAdmin(Actor actor) {
        List<Dto.WorkspaceView> workspaces = fixture.whoami(actor).workspaces();
        boolean admin = workspaces.stream().anyMatch(w ->
                WorkspaceRole.OWNER.name().equals(w.role()) || WorkspaceRole.ADMIN.name().equals(w.role()));
        if (!admin) {
            audit.deny(actor, "verification.read", "tenant", actor.tenantId(), ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "取证端点仅空间 OWNER/ADMIN 可用");
        }
    }
}
