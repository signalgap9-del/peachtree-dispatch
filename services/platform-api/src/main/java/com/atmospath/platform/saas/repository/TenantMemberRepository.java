package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.MemberRole;
import com.atmospath.platform.saas.entity.TenantMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {

    Optional<TenantMember> findByTenantIdAndEmailAndDeletedAtIsNull(UUID tenantId, String email);

    List<TenantMember> findByTenantIdAndDeletedAtIsNullOrderByRoleDesc(UUID tenantId);

    List<TenantMember> findByTenantIdAndRoleAndDeletedAtIsNull(UUID tenantId, MemberRole role);

    /**
     * RBAC decision delegated to the database function so every caller
     * (API, CDC workers, future services) shares one policy source.
     * The function probes saved_route/saved_place by resource id, so no
     * resource type argument is needed; actions are lowercase
     * ({@code read}, {@code write}, {@code delete}, {@code admin}).
     */
    @Query(nativeQuery = true, value = """
            SELECT has_permission(CAST(:memberId AS uuid), CAST(:resourceId AS uuid), :action)
            """)
    boolean hasPermission(@Param("memberId") UUID memberId,
                          @Param("resourceId") UUID resourceId,
                          @Param("action") String action);
}
