package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEscalation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEscalationRepository extends JpaRepository<AlertEscalation, UUID> {

    List<AlertEscalation> findByAlertEventIdOrderByLevelAsc(UUID alertEventId);

    List<AlertEscalation> findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull(UUID alertEventId, UUID targetMemberId);
}
