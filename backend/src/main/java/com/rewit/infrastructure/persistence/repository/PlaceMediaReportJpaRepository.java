package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para place_media_reports (V31).
 */
public interface PlaceMediaReportJpaRepository extends JpaRepository<PlaceMediaReportJpaEntity, UUID> {

    boolean existsByPlaceMediaIdAndReporterUserId(UUID placeMediaId, UUID reporterUserId);

    long countByPlaceMediaIdAndStatus(UUID placeMediaId, String status);

    List<PlaceMediaReportJpaEntity> findByPlaceMediaId(UUID placeMediaId);

    List<PlaceMediaReportJpaEntity> findByStatusOrderByCreatedAtAsc(String status);
}

