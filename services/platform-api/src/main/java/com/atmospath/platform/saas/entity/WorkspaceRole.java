package com.atmospath.platform.saas.entity;

/**
 * Workspace-scoped role hierarchy (distinct from the tenant-level
 * {@link MemberRole}): ADMIN > EDITOR > VIEWER.
 */
public enum WorkspaceRole {
    VIEWER(1),
    EDITOR(2),
    ADMIN(3);

    private final int rank;

    WorkspaceRole(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(WorkspaceRole required) {
        return rank >= required.rank;
    }
}
