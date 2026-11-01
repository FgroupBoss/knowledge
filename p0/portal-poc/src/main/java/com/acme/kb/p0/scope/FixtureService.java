package com.acme.kb.p0.scope;

import com.acme.kb.p0.api.Dto;
import com.acme.kb.p0.audit.AuditService;
import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.auth.IdentityService;
import com.acme.kb.p0.auth.WorkspaceRole;
import com.acme.kb.p0.authz.AclMaintenance;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.Classification;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.KnowledgeBaseMapper;
import com.acme.kb.p0.repo.MemberMapper;
import com.acme.kb.p0.repo.ScopeCommandMapper;
import com.acme.kb.p0.repo.TenantMapper;
import com.acme.kb.p0.repo.UserMapper;
import com.acme.kb.p0.repo.WorkspaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 验证夹具的身份/范围写入。这些操作在 P1 由 IAM 与 OIDC 供给，P0 用同一套表与同一套判定代码，
 * 因此这里只负责"造事实"，绝不绕过 AuthorizationService 去读事实。
 * 所有写入都限定在调用主体自己的租户内，跨租户主体创建只能由夹具端点以独立口令发起。
 */
@Service
public class FixtureService {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Pattern USER_GRANT = Pattern.compile("READ|WRITE");

    private final TenantMapper tenants;
    private final UserMapper users;
    private final WorkspaceMapper workspaces;
    private final MemberMapper members;
    private final KnowledgeBaseMapper knowledgeBases;
    private final ScopeCommandMapper scope;
    private final DocumentMapper documents;
    private final AclMaintenance maintenance;
    private final AuditService audit;
    private final P0Properties props;

    public FixtureService(TenantMapper tenants, UserMapper users, WorkspaceMapper workspaces,
                          MemberMapper members, KnowledgeBaseMapper knowledgeBases, ScopeCommandMapper scope,
                          DocumentMapper documents, AclMaintenance maintenance, AuditService audit,
                          P0Properties props) {
        this.tenants = tenants;
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
        this.knowledgeBases = knowledgeBases;
        this.scope = scope;
        this.documents = documents;
        this.maintenance = maintenance;
        this.audit = audit;
        this.props = props;
    }

    /** 当前主体可见的范围：只列出真正有成员关系的空间与其 KB。 */
    public Dto.Principal whoami(Actor actor) {
        List<Dto.WorkspaceView> views = new ArrayList<>();
        for (MemberMapper.Row membership : members.listForUserInTenant(actor.tenantId(), actor.userId())) {
            WorkspaceMapper.Row ws = workspaces.findById(membership.workspaceId());
            if (ws == null || !ws.tenantId().equals(actor.tenantId())) {
                continue;
            }
            List<Dto.KnowledgeBaseView> kbs = new ArrayList<>();
            for (KnowledgeBaseMapper.Row kb : knowledgeBases.listByWorkspace(ws.id())) {
                if (!"ACTIVE".equals(kb.status())) {
                    continue;
                }
                kbs.add(toView(kb));
            }
            views.add(new Dto.WorkspaceView(ws.id(), ws.code(), ws.name(), membership.role(), kbs));
        }
        return new Dto.Principal(actor.tenantCode(), actor.userCode(), actor.clearance().name(), views);
    }

    @Transactional
    public Dto.WorkspaceView createWorkspace(Actor actor, String code, String name) {
        requireCode(code);
        WorkspaceMapper.Row existing = workspaces.findByCode(actor.tenantId(), code);
        if (existing != null) {
            return new Dto.WorkspaceView(existing.id(), existing.code(), existing.name(),
                    roleOf(existing.id(), actor.userId()), List.of());
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        scope.insertWorkspace(id, actor.tenantId(), code, safeName(name, code), actor.userId(), now);
        scope.upsertMember(UUID.randomUUID().toString(), id, actor.userId(), WorkspaceRole.OWNER.name(), now);
        audit.record(actor, "workspace.create", "workspace", id, "ALLOW", null, Map.of("code", code));
        return new Dto.WorkspaceView(id, code, safeName(name, code), WorkspaceRole.OWNER.name(), List.of());
    }

    @Transactional
    public Dto.KnowledgeBaseView createKnowledgeBase(Actor actor, String workspaceId, String code,
                                                    String name, String classification) {
        requireCode(code);
        requireWorkspaceManage(actor, workspaceId);
        KnowledgeBaseMapper.Row existing = knowledgeBases.findByCode(actor.tenantId(), workspaceId, code);
        if (existing != null) {
            return toView(existing);
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        scope.insertKnowledgeBase(id, actor.tenantId(), workspaceId, code, safeName(name, code),
                props.engine().provider(), Classification.parse(classification).name(), now);
        audit.record(actor, "knowledge_base.create", "knowledge_base", id, "ALLOW", null,
                Map.of("workspaceId", workspaceId, "classification",
                        Classification.parse(classification).name()));
        KnowledgeBaseMapper.Row created = knowledgeBases.findById(id);
        return toView(created == null
                ? new KnowledgeBaseMapper.Row(id, actor.tenantId(), workspaceId, code, safeName(name, code),
                props.engine().provider(), null, Classification.parse(classification), 1, "ACTIVE")
                : created);
    }

    /** 幂等增加/改角色；变更后立即递增 acl_version 并失效缓存，撤权不留 TTL 窗口。 */
    @Transactional
    public void addMember(Actor actor, String workspaceId, String userCode, String role) {
        requireMemberManage(actor, workspaceId);
        WorkspaceRole parsed = parseRole(role);
        String userId = resolveUserInTenant(actor.tenantId(), userCode);
        scope.upsertMember(UUID.randomUUID().toString(), workspaceId, userId, parsed.name(), Instant.now());
        maintenance.onWorkspaceMembershipChanged(workspaceId);
        audit.record(actor, "member.grant", "workspace", workspaceId, "ALLOW", null,
                Map.of("target", userCode, "role", parsed.name()));
    }

    @Transactional
    public void removeMember(Actor actor, String workspaceId, String userCode) {
        requireMemberManage(actor, workspaceId);
        String userId = resolveUserInTenant(actor.tenantId(), userCode);
        int removed = scope.deleteMember(workspaceId, userId);
        maintenance.onWorkspaceMembershipChanged(workspaceId);
        audit.record(actor, "member.revoke", "workspace", workspaceId, removed > 0 ? "ALLOW" : "DENY",
                removed > 0 ? null : ErrorCode.RESOURCE_NOT_FOUND.name(),
                Map.of("target", userCode));
    }

    /** 文档级 ACL：启用后仅名单内主体可读，越权在 Prompt 之前就被剔除。 */
    @Transactional
    public void grantDocumentAcl(Actor actor, String documentId, String userCode, String grant) {
        DocumentMapper.Row doc = documents.findById(documentId);
        if (doc == null || !doc.tenantId().equals(actor.tenantId()) || "DELETED".equals(doc.status())) {
            throw ApiException.notFound("文档");
        }
        requireKbWrite(actor, doc.kbId());
        if (grant == null || !USER_GRANT.matcher(grant.strip().toUpperCase()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "grant 只能是 READ 或 WRITE");
        }
        String userId = resolveUserInTenant(doc.tenantId(), userCode);
        Instant now = Instant.now();
        scope.upsertDocumentAcl(UUID.randomUUID().toString(), doc.tenantId(), documentId, userId,
                grant.strip().toUpperCase(), now);
        scope.enforceDocumentAcl(documentId, now);
        maintenance.onKbAclChanged(doc.kbId());
        audit.record(actor, "document.acl.grant", "document", documentId, "ALLOW", null,
                Map.of("target", userCode, "grant", grant.strip().toUpperCase()));
    }

    /**
     * 冷启动基线：库为空时建立引导租户与其 OWNER，使验证脚本能通过 HTTP 构造其余夹具。
     * 已在 Compose 里固定口令，重复执行只会命中已有行。
     */
    @Transactional
    public boolean bootstrapIfEmpty(String tenantCode, String adminCode) {
        if (tenants.count() > 0) {
            return false;
        }
        requireCode(tenantCode);
        requireCode(adminCode);
        Instant now = Instant.now();
        String tenantId = UUID.randomUUID().toString();
        scope.insertTenant(tenantId, tenantCode, "引导租户 " + tenantCode, now);
        String userId = UUID.randomUUID().toString();
        scope.insertUser(userId, tenantId, IdentityService.TEST_ISSUER, adminCode, "引导管理员",
                Classification.TOP_SECRET.name(), now);
        String workspaceId = UUID.randomUUID().toString();
        scope.insertWorkspace(workspaceId, tenantId, "core", "引导空间", userId, now);
        scope.upsertMember(UUID.randomUUID().toString(), workspaceId, userId, WorkspaceRole.OWNER.name(), now);
        return true;
    }

    /** 夹具专用：在指定租户下建用户（跨租户越权样例需要两个租户）。 */
    @Transactional
    public void ensurePrincipal(String tenantCode, String userCode, String displayName, String clearance) {
        requireCode(tenantCode);
        requireCode(userCode);
        TenantMapper.Row tenant = tenants.findByCode(tenantCode);
        if (tenant == null) {
            Instant now = Instant.now();
            String id = UUID.randomUUID().toString();
            scope.insertTenant(id, tenantCode, "租户 " + tenantCode, now);
            tenant = new TenantMapper.Row(id, tenantCode, "租户 " + tenantCode, "ACTIVE");
        }
        if (users.findByTenantAndCode(tenantCode, userCode) != null) {
            return;
        }
        scope.insertUser(UUID.randomUUID().toString(), tenant.id(), IdentityService.TEST_ISSUER, userCode,
                displayName == null || displayName.isBlank() ? userCode : trim(displayName, 128),
                Classification.parse(clearance).name(), Instant.now());
    }

    @Transactional
    public void ensureTenant(String tenantCode, String name) {
        requireCode(tenantCode);
        if (tenants.findByCode(tenantCode) != null) {
            return;
        }
        scope.insertTenant(UUID.randomUUID().toString(), tenantCode,
                name == null || name.isBlank() ? "租户 " + tenantCode : trim(name, 128), Instant.now());
    }

    /** 夹具：把测试身份头里的 userCode 换成内部 user id（验证脚本需要精确操作缓存键）。 */
    public String userIdOf(String tenantId, String userCode) {
        return resolveUserInTenant(tenantId, userCode);
    }

    public List<Dto.KnowledgeBaseView> listKnowledgeBases(Actor actor) {
        List<Dto.KnowledgeBaseView> out = new ArrayList<>();
        for (KnowledgeBaseMapper.Row kb : knowledgeBases.listReadableForUser(actor.tenantId(), actor.userId())) {
            if (actor.clearance().atLeast(kb.classification())) {
                out.add(toView(kb));
            }
        }
        return out;
    }

    private void requireKbWrite(Actor actor, String kbId) {
        KnowledgeBaseMapper.Row kb = knowledgeBases.findById(kbId);
        if (kb == null || !kb.tenantId().equals(actor.tenantId())) {
            throw ApiException.notFound("知识库");
        }
        MemberMapper.Row membership = members.find(kb.workspaceId(), actor.userId());
        WorkspaceRole role = membership == null ? null : WorkspaceRole.parse(membership.role());
        if (role == null || !role.allows("kb:write")) {
            audit.deny(actor, "document.acl.grant", "document_acl", kbId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许配置文档 ACL");
        }
    }

    private void requireWorkspaceManage(Actor actor, String workspaceId) {
        requirePermission(actor, workspaceId, "workspace:manage", "workspace.create");
    }

    private void requireMemberManage(Actor actor, String workspaceId) {
        requirePermission(actor, workspaceId, "member:manage", "member.grant");
    }

    private void requirePermission(Actor actor, String workspaceId, String permission, String action) {
        WorkspaceMapper.Row ws = workspaces.findById(workspaceId);
        if (ws == null || !"ACTIVE".equals(ws.status())) {
            throw ApiException.notFound("工作空间");
        }
        if (!ws.tenantId().equals(actor.tenantId())) {
            // 响应仍是 404（不泄露存在性），但必须留下跨租户痕迹供取证
            audit.deny(actor, action, "workspace", workspaceId, ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("工作空间");
        }
        MemberMapper.Row membership = members.find(workspaceId, actor.userId());
        WorkspaceRole role = membership == null ? null : WorkspaceRole.parse(membership.role());
        if (role == null || !role.allows(permission)) {
            audit.deny(actor, action, "workspace", workspaceId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许该操作");
        }
    }

    private String resolveUserInTenant(String tenantId, String userCode) {
        TenantMapper.Row tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "租户上下文缺失");
        }
        String userId = scope.userIdByCode(tenant.code(), userCode);
        if (userId == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "目标用户不在本租户内");
        }
        return userId;
    }

    private String roleOf(String workspaceId, String userId) {
        MemberMapper.Row row = members.find(workspaceId, userId);
        return row == null ? null : row.role();
    }

    private static Dto.KnowledgeBaseView toView(KnowledgeBaseMapper.Row kb) {
        return new Dto.KnowledgeBaseView(kb.id(), kb.workspaceId(), kb.code(), kb.name(),
                kb.classification().name(), kb.aclVersion(), kb.engineKbId(), kb.status());
    }

    private static WorkspaceRole parseRole(String role) {
        try {
            return WorkspaceRole.parse(role);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "角色取值不合法");
        }
    }

    private static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "标识只允许小写字母、数字、下划线与短横线");
        }
    }

    private static String safeName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : trim(name, 128);
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
