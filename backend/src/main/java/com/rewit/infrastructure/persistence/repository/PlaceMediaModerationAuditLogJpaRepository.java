package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceMediaModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaceMediaModerationAuditLogJpaRepository extends JpaRepository<PlaceMediaModerationAuditLogJpaEntity, UUID> {

    List<PlaceMediaModerationAuditLogJpaEntity> findByPlaceMediaIdOrderByCreatedAtAsc(UUID placeMediaId);

    List<PlaceMediaModerationAuditLogJpaEntity> findByPlaceIdOrderByCreatedAtAsc(UUID placeId);
}

