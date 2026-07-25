package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    List<Workspace> findByTenantId(UUID tenantId);
}
