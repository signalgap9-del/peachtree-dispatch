package com.atmospath.platform.saas.service;

import java.util.Locale;
import java.util.UUID;

import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.entity.Workspace;
import com.atmospath.platform.saas.entity.WorkspaceMember;
import com.atmospath.platform.saas.entity.WorkspaceRole;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import com.atmospath.platform.saas.repository.WorkspaceMemberRepository;
import com.atmospath.platform.saas.repository.WorkspaceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC checks. Resource permissions are delegated to the
 * {@code has_permission()} database function so every caller shares one
 * policy source; workspace checks apply the workspace role hierarchy
 * (ADMIN > EDITOR > VIEWER) with a tenant OWNER/ADMIN override.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class PermissionService {

    private final TenantMemberRepository tenantMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;

    public PermissionService(TenantMemberRepository tenantMemberRepository,
                             WorkspaceMemberRepository workspaceMemberRepository,
                             WorkspaceRepository workspaceRepository) {
        this.tenantMemberRepository = tenantMemberRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Resource-level check delegated to the database function. The function
     * resolves the resource across saved_route/saved_place itself, so
     * {@code resourceType} is advisory for callers and auditing only.
     *
     * @param action {@code READ}, {@code WRITE}, {@code DELETE}, or {@code ADMIN}
     *               (case-insensitive; normalized to lowercase for the function)
     */
    @Transactional(value = "saasTransactionManager", readOnly = true)
    public boolean hasPermission(UUID memberId, String resourceType, UUID resourceId, String action) {
        return tenantMemberRepository.hasPermission(
                memberId, resourceId, action.toLowerCase(Locale.ROOT));
    }

    /**
     * Workspace access with role hierarchy: the member's workspace role must
     * be at least {@code requiredRole}. Tenant OWNER/ADMIN implicitly inherit
     * access to every workspace in their tenant.
     */
    @Transactional(value = "saasTransactionManager", readOnly = true)
    public boolean checkWorkspaceAccess(UUID memberId, UUID workspaceId, WorkspaceRole requiredRole) {
        var workspaceAccess = workspaceMemberRepository.findByMemberIdAndWorkspaceId(memberId, workspaceId);
        if (workspaceAccess.isPresent()) {
            return workspaceAccess.get().getRole().atLeast(requiredRole);
        }
        // Tenant OWNER/ADMIN override: full access inside their own tenant.
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return false;
        }
        TenantMember member = tenantMemberRepository.findById(memberId).orElse(null);
        if (member == null || member.getDeletedAt() != null
                || !member.getTenantId().equals(workspace.getTenantId())) {
            return false;
        }
        return member.getRole() == com.atmospath.platform.saas.entity.MemberRole.OWNER
                || member.getRole() == com.atmospath.platform.saas.entity.MemberRole.ADMIN;
    }
}
