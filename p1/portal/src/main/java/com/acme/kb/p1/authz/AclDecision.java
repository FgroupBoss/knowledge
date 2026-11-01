package com.acme.kb.p1.authz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;

/** 缓存的授权决策快照；aclVersion 必须不低于 KB 水位才可使用。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AclDecision(
        String kbId,
        String userId,
        String tenantId,
        String workspaceId,
        String role,
        Set<String> permissions,
        String classification,
        long aclVersion,
        boolean exists,
        long cachedAt) {

    public boolean allows(String permission) {
        return exists && permissions != null && permissions.contains(permission);
    }
}
