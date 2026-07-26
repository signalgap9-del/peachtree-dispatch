package com.atmospath.platform.saas.repository;

import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    Optional<WorkspaceMember> findByMemberIdAndWorkspaceId(UUID memberId, UUID workspaceId);

    void deleteByMemberId(UUID memberId);
}
