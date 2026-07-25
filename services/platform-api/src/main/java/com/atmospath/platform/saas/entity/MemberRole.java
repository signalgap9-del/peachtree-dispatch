package com.atmospath.platform.saas.entity;

/**
 * Role hierarchy inside a tenant. Higher rank implies every permission of a
 * lower rank: OWNER > ADMIN > MEMBER.
 */
public enum MemberRole {
    MEMBER(1),
    ADMIN(2),
    OWNER(3);

    private final int rank;

    MemberRole(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(MemberRole required) {
        return rank >= required.rank;
    }
}
