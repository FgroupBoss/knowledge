package com.acme.kb.p1.auth;

import com.acme.kb.p1.common.Classification;

/**
 * 服务端解析出的调用主体。客户端提交的 tenantId/workspaceId/kbIds 一律不可信，
 * 只能与 Actor 的授权集合求交（见 AuthorizationService）。
 */
public record Actor(
        String userId,
        String tenantId,
        String tenantCode,
        String userCode,
        String displayName,
        Classification clearance) {

    public String key() {
        return tenantCode + ":" + userCode;
    }
}
