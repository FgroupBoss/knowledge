package com.acme.kb.p1.authz;

import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.WorkspaceRole;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.DocumentAclMapper;
import com.acme.kb.p1.repo.DocumentMapper;
import com.acme.kb.p1.repo.KnowledgeBaseMapper;
import com.acme.kb.p1.repo.MemberMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 唯一的授权判定点：默认拒绝，逐层校验租户边界 → 空间成员与角色权限位 → 密级 → 资源状态 → 文档级 ACL。
 * 跨租户访问按 404 响应（不泄露资源是否存在），但审计记 CROSS_TENANT_ACCESS。
 */
@Service
public class AuthorizationService {

    private final KnowledgeBaseMapper kbs;
    private final MemberMapper members;
    private final DocumentAclMapper docAcls;
    private final DocumentMapper documents;
    private final AclCacheService cache;
    private final AuditService audit;

    public AuthorizationService(KnowledgeBaseMapper kbs, MemberMapper members, DocumentAclMapper docAcls,
                                DocumentMapper documents, AclCacheService cache, AuditService audit) {
        this.kbs = kbs;
        this.members = members;
        this.docAcls = docAcls;
        this.documents = documents;
        this.cache = cache;
        this.audit = audit;
    }

    public AclDecision requireKbPermission(Actor actor, String kbId, String permission) {
        AclDecision decision = decide(actor, kbId);
        if (!decision.exists()) {
            throw ApiException.notFound("知识库");
        }
        if (!decision.tenantId().equals(actor.tenantId())) {
            audit.deny(actor, "kb." + permission, "knowledge_base", kbId, ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("知识库");
        }
        if (decision.role() == null) {
            audit.deny(actor, "kb." + permission, "knowledge_base", kbId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "无权访问该知识库");
        }
        if (!decision.allows(permission)) {
            audit.deny(actor, "kb." + permission, "knowledge_base", kbId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许该操作");
        }
        if (!actor.clearance().atLeast(Classification.parse(decision.classification()))) {
            audit.deny(actor, "kb." + permission, "knowledge_base", kbId, ErrorCode.CLEARANCE_TOO_LOW.name());
            throw new ApiException(ErrorCode.CLEARANCE_TOO_LOW, "密级不足");
        }
        return decision;
    }

    /**
     * 每次判定先读一次 KB 权威行（主键查询），用它的 acl_version 拼缓存键。
     * 命中后仍用 DB 行的空间/密级/状态覆盖缓存快照，避免缓存放行已下线或已改密的 KB。
     */
    private AclDecision decide(Actor actor, String kbId) {
        KnowledgeBaseMapper.Row kb = kbs.findById(kbId);
        if (kb == null) {
            return missing(kbId, actor);
        }
        if (!"ACTIVE".equals(kb.status()) || !kb.tenantId().equals(actor.tenantId())) {
            return fromRow(kb, actor);
        }
        var cached = cache.find(kbId, actor.userId(), kb.aclVersion());
        if (cached.isPresent()) {
            AclDecision d = cached.get();
            if (actor.tenantId().equals(d.tenantId()) && d.exists()
                    && kb.workspaceId().equals(d.workspaceId())
                    && kb.classification().name().equals(d.classification())) {
                return new AclDecision(d.kbId(), d.userId(), kb.tenantId(), kb.workspaceId(), d.role(),
                        d.permissions(), kb.classification().name(), kb.aclVersion(), true, d.cachedAt());
            }
        }
        AclDecision fresh = fromRow(kb, actor);
        cache.store(fresh);
        return fresh;
    }

    private AclDecision fromRow(KnowledgeBaseMapper.Row kb, Actor actor) {
        // 跨租户：不返回任何 kb 内容，用哨兵 exists=false，调用方按 404 处理
        if (!kb.tenantId().equals(actor.tenantId())) {
            return new AclDecision(kb.id(), actor.userId(), kb.tenantId(), null, null, Set.of(),
                    null, kb.aclVersion(), false, System.currentTimeMillis());
        }
        if (!"ACTIVE".equals(kb.status())) {
            return new AclDecision(kb.id(), actor.userId(), kb.tenantId(), kb.workspaceId(), null, Set.of(),
                    kb.classification().name(), kb.aclVersion(), false, System.currentTimeMillis());
        }
        MemberMapper.Row membership = members.find(kb.workspaceId(), actor.userId());
        WorkspaceRole role = membership == null ? null : WorkspaceRole.parse(membership.role());
        Set<String> permissions = role == null ? Set.of() : role.getPermissions();
        return new AclDecision(kb.id(), actor.userId(), kb.tenantId(), kb.workspaceId(),
                role == null ? null : role.name(), permissions, kb.classification().name(),
                kb.aclVersion(), true, System.currentTimeMillis());
    }

    private AclDecision missing(String kbId, Actor actor) {
        return new AclDecision(kbId, actor.userId(), actor.tenantId(), null, null, Set.of(),
                null, 0, false, System.currentTimeMillis());
    }

    /** 客户端提交的 KB 范围只作提示，与授权集合求交，越界项静默丢弃并计数。 */
    public ScopedScope scope(Actor actor, List<String> requestedKbIds, String permission) {
        Set<String> authorized = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();
        if (requestedKbIds == null || requestedKbIds.isEmpty()) {
            for (KnowledgeBaseMapper.Row kb : kbs.listReadableForUser(actor.tenantId(), actor.userId())) {
                if (actor.clearance().atLeast(kb.classification()) && has(kb.id(), permission, actor)) {
                    authorized.add(kb.id());
                }
            }
            return new ScopedScope(List.copyOf(authorized), dropped);
        }
        for (String kbId : new LinkedHashSet<>(requestedKbIds)) {
            try {
                requireKbPermission(actor, kbId, permission);
                authorized.add(kbId);
            } catch (ApiException e) {
                dropped.add(kbId + "=" + e.code().name());
            }
        }
        return new ScopedScope(List.copyOf(authorized), dropped);
    }

    private boolean has(String kbId, String permission, Actor actor) {
        MemberMapper.Row membership = membershipOfKb(kbId, actor);
        if (membership == null) {
            return false;
        }
        return WorkspaceRole.parse(membership.role()).allows(permission);
    }

    private MemberMapper.Row membershipOfKb(String kbId, Actor actor) {
        KnowledgeBaseMapper.Row kb = kbs.findById(kbId);
        return kb == null ? null : members.find(kb.workspaceId(), actor.userId());
    }

    /** 文档读取：空间/KB 权限 + 密级 + 文档级 ACL（若启用）+ 状态非 DELETED。 */
    public DocumentMapper.Row requireDocumentRead(Actor actor, String documentId) {
        DocumentMapper.Row doc = documents.findById(documentId);
        if (doc == null) {
            throw ApiException.notFound("文档");
        }
        if (!doc.tenantId().equals(actor.tenantId())) {
            audit.deny(actor, "document.read", "document", documentId, ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("文档");
        }
        if ("DELETED".equals(doc.status())) {
            throw ApiException.notFound("文档");
        }
        requireKbPermission(actor, doc.kbId(), "kb:read");
        if (!actor.clearance().atLeast(Classification.parse(doc.classification()))) {
            audit.deny(actor, "document.read", "document", documentId, ErrorCode.CLEARANCE_TOO_LOW.name());
            throw new ApiException(ErrorCode.CLEARANCE_TOO_LOW, "密级不足");
        }
        if (doc.aclEnforced()) {
            List<String> grants = docAcls.grantsOf(documentId, actor.userId());
            if (grants.isEmpty()) {
                audit.deny(actor, "document.read", "document", documentId, ErrorCode.ACCESS_DENIED.name());
                throw new ApiException(ErrorCode.ACCESS_DENIED, "文档级 ACL 未授权");
            }
        }
        return doc;
    }

    /** 写入：需要 kb:write，并按文档级 ACL 收紧。 */
    public DocumentMapper.Row requireDocumentWrite(Actor actor, String documentId) {
        DocumentMapper.Row doc = requireDocumentRead(actor, documentId);
        AclDecision kb = decide(actor, doc.kbId());
        if (!kb.allows("kb:write")) {
            audit.deny(actor, "document.write", "document", documentId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许修改该文档");
        }
        return doc;
    }

    /** 删除比写入更高门槛：只有持有 kb:delete 的角色可以发起删除 Saga。 */
    public DocumentMapper.Row requireDocumentDelete(Actor actor, String documentId) {
        DocumentMapper.Row doc = requireDocumentRead(actor, documentId);
        AclDecision kb = decide(actor, doc.kbId());
        if (!kb.allows("kb:delete")) {
            audit.deny(actor, "document.delete", "document", documentId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许删除该文档");
        }
        return doc;
    }

    public record ScopedScope(List<String> authorizedKbIds, List<String> droppedKbIds) {
    }

    public KnowledgeBaseMapper.Row kbOf(String kbId) {
        KnowledgeBaseMapper.Row kb = kbs.findById(kbId);
        if (kb == null) {
            throw ApiException.notFound("知识库");
        }
        return kb;
    }
}
