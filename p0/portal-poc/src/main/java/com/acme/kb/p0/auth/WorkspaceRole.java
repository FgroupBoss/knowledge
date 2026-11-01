package com.acme.kb.p0.auth;

import java.util.Set;

/** 空间角色与权限位；默认拒绝，未列出的权限即无权限。 */
public enum WorkspaceRole {
    OWNER(4, Set.of("kb:read", "kb:write", "kb:delete", "workspace:read", "workspace:manage", "member:manage")),
    ADMIN(3, Set.of("kb:read", "kb:write", "kb:delete", "workspace:read", "workspace:manage", "member:manage")),
    CONTRIBUTOR(2, Set.of("kb:read", "kb:write", "workspace:read")),
    VIEWER(1, Set.of("kb:read", "workspace:read"));

    private final int rank;
    private final Set<String> permissions;

    WorkspaceRole(int rank, Set<String> permissions) {
        this.rank = rank;
        this.permissions = permissions;
    }

    public boolean allows(String permission) {
        return permissions.contains(permission);
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public boolean atLeast(WorkspaceRole other) {
        return rank >= other.rank;
    }

    public static WorkspaceRole parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("role required");
        }
        return valueOf(raw.strip().toUpperCase());
    }
}
