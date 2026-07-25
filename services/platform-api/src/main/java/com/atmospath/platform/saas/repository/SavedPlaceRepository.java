package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.SavedPlaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedPlaceRepository extends JpaRepository<SavedPlaceEntity, UUID> {

    List<SavedPlaceEntity> findByMemberIdAndDeletedAtIsNull(UUID memberId);

    List<SavedPlaceEntity> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);
}
