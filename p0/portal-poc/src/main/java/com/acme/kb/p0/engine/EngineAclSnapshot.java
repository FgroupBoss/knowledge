package com.acme.kb.p0.engine;

import com.acme.kb.p0.repo.EngineStoreMapper;
import org.springframework.stereotype.Component;

/**
 * 引擎侧 ACL 快照版本探针。业务层只通过本组件询问"引擎的授权快照是否还追得上业务库"，
 * 不直接读引擎自己的表。
 */
@Component
public class EngineAclSnapshot {

    private final EngineStoreMapper store;

    public EngineAclSnapshot(EngineStoreMapper store) {
        this.store = store;
    }

    /** 返回 true 表示引擎记录的 acl_version 低于业务库当前值，其召回结果不得进入 Prompt。 */
    public boolean stale(String engineKbId, long authoritativeAclVersion) {
        Long snapshot = engineKbId == null ? null : store.aclVersionOfEngineKb(engineKbId);
        return snapshot == null || snapshot < authoritativeAclVersion;
    }
}
