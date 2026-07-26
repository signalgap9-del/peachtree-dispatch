package com.atmospath.platform.saas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.MemberRole;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.entity.Workspace;
import com.atmospath.platform.saas.entity.WorkspaceMember;
import com.atmospath.platform.saas.entity.WorkspaceRole;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import com.atmospath.platform.saas.repository.WorkspaceMemberRepository;
import com.atmospath.platform.saas.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionServiceTests {

    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();

    private TenantMemberRepository tenantMemberRepo;
    private WorkspaceMemberRepository workspaceMemberRepo;
    private WorkspaceRepository workspaceRepo;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        tenantMemberRepo = mock(TenantMemberRepository.class);
        workspaceMemberRepo = mock(WorkspaceMemberRepository.class);
        workspaceRepo = mock(WorkspaceRepository.class);
        service = new PermissionService(tenantMemberRepo, workspaceMemberRepo, workspaceRepo);
    }

    @Test
    void hasPermissionDelegatesToDatabaseFunction() {
        when(tenantMemberRepo.hasPermission(MEMBER_ID, RESOURCE_ID, "read")).thenReturn(true);

        assertThat(service.hasPermission(MEMBER_ID, "saved_route", RESOURCE_ID, "READ")).isTrue();
    }

    @Test
    void hasPermissionReturnsFalseWhenDenied() {
        when(tenantMemberRepo.hasPermission(MEMBER_ID, RESOURCE_ID, "delete")).thenReturn(false);

        assertThat(service.hasPermission(MEMBER_ID, "saved_route", RESOURCE_ID, "DELETE")).isFalse();
    }

    @Test
    void workspaceAccessGrantedByWorkspaceRole() {
        WorkspaceMember wm = mock(WorkspaceMember.class);
        when(wm.getRole()).thenReturn(WorkspaceRole.ADMIN);
        when(workspaceMemberRepo.findByMemberIdAndWorkspaceId(MEMBER_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(wm));

        assertThat(service.checkWorkspaceAccess(MEMBER_ID, WORKSPACE_ID, WorkspaceRole.EDITOR)).isTrue();
    }

    @Test
    void workspaceAccessDeniedByInsufficientRole() {
        WorkspaceMember wm = mock(WorkspaceMember.class);
        when(wm.getRole()).thenReturn(WorkspaceRole.VIEWER);
        when(workspaceMemberRepo.findByMemberIdAndWorkspaceId(MEMBER_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(wm));

        assertThat(service.checkWorkspaceAccess(MEMBER_ID, WORKSPACE_ID, WorkspaceRole.ADMIN)).isFalse();
    }

    @Test
    void tenantAdminGetsImplicitWorkspaceAccess() {
        when(workspaceMemberRepo.findByMemberIdAndWorkspaceId(MEMBER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        Workspace workspace = mock(Workspace.class);
        when(workspace.getTenantId()).thenReturn(TENANT_ID);
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        TenantMember member = mock(TenantMember.class);
        when(member.getTenantId()).thenReturn(TENANT_ID);
        when(member.getRole()).thenReturn(MemberRole.ADMIN);
        when(member.getDeletedAt()).thenReturn(null);
        when(tenantMemberRepo.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThat(service.checkWorkspaceAccess(MEMBER_ID, WORKSPACE_ID, WorkspaceRole.EDITOR)).isTrue();
    }

    @Test
    void tenantMemberFromDifferentTenantDenied() {
        when(workspaceMemberRepo.findByMemberIdAndWorkspaceId(MEMBER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        Workspace workspace = mock(Workspace.class);
        when(workspace.getTenantId()).thenReturn(TENANT_ID);
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        TenantMember member = mock(TenantMember.class);
        when(member.getTenantId()).thenReturn(UUID.randomUUID()); // different tenant
        when(member.getRole()).thenReturn(MemberRole.ADMIN);
        when(member.getDeletedAt()).thenReturn(null);
        when(tenantMemberRepo.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThat(service.checkWorkspaceAccess(MEMBER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER)).isFalse();
    }

    @Test
    void unknownWorkspaceDenied() {
        when(workspaceMemberRepo.findByMemberIdAndWorkspaceId(MEMBER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(service.checkWorkspaceAccess(MEMBER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER)).isFalse();
    }
}
