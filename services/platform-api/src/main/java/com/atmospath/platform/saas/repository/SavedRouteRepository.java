package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.SavedRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedRouteRepository extends JpaRepository<SavedRouteEntity, UUID> {

    List<SavedRouteEntity> findByMemberIdAndDeletedAtIsNull(UUID memberId);

    List<SavedRouteEntity> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);
}
