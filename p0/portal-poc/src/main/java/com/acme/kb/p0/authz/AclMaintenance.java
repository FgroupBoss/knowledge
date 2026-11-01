package com.acme.kb.p0.authz;

import com.acme.kb.p0.repo.EngineStoreMapper;
import com.acme.kb.p0.repo.KnowledgeBaseMapper;
import com.acme.kb.p0.repo.ScopeCommandMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 授权边界变更后的收敛动作，顺序不可调换：
 * 先在 MySQL 递增 acl_version（事实源），再用新版本号抬升 Redis 水位并删除受影响决策键。
 * 反过来写会留下"水位低于缓存值"的窗口，令旧权限被误判为新鲜。
 */
@Service
public class AclMaintenance {

    private final KnowledgeBaseMapper kbs;
    private final ScopeCommandMapper scope;
    private final AclCacheService cache;
    private final EngineStoreMapper engineStore;

    public AclMaintenance(KnowledgeBaseMapper kbs, ScopeCommandMapper scope, AclCacheService cache,
                          EngineStoreMapper engineStore) {
        this.kbs = kbs;
        this.scope = scope;
        this.cache = cache;
        this.engineStore = engineStore;
    }

    @Transactional
    public void onWorkspaceMembershipChanged(String workspaceId) {
        kbs.bumpAclVersionOfWorkspace(workspaceId);
        for (KnowledgeBaseMapper.Row kb : kbs.listByWorkspace(workspaceId)) {
            invalidate(kb);
        }
    }

    @Transactional
    public void onKbAclChanged(String kbId) {
        kbs.bumpAclVersionOfKb(kbId);
        KnowledgeBaseMapper.Row kb = kbs.findById(kbId);
        if (kb != null) {
            invalidate(kb);
        }
    }

    private void invalidate(KnowledgeBaseMapper.Row kb) {
        Long version = kbs.aclVersion(kb.id(), kb.tenantId());
        List<String> userIds = scope.memberUserIdsOfKb(kb.id());
        cache.invalidate(kb.id(), version == null ? 0 : version, userIds);
        // 引擎快照必须一起追平，否则每次成员/ACL 变更都会把整个 KB 的召回永久打成"过期"。
        // 内置引擎与业务库同库，抬版即等于交付；换成外部引擎后这一步是推送调用，失败则保持低版本。
        if (kb.engineKbId() != null && version != null) {
            engineStore.syncAclVersion(kb.engineKbId(), version);
        }
    }
}
