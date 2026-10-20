package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para place_media_reports (V31).
 */
public interface PlaceMediaReportJpaRepository extends JpaRepository<PlaceMediaReportJpaEntity, UUID> {

    boolean existsByPlaceMediaIdAndReporterUserId(UUID placeMediaId, UUID reporterUserId);

    long countByPlaceMediaIdAndStatus(UUID placeMediaId, String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM PlaceMediaReportJpaEntity r WHERE r.id = :id")
    java.util.Optional<PlaceMediaReportJpaEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM PlaceMediaReportJpaEntity r WHERE r.placeMediaId = :placeMediaId ORDER BY r.id ASC")
    List<PlaceMediaReportJpaEntity> findByPlaceMediaIdForUpdate(@org.springframework.data.repository.query.Param("placeMediaId") UUID placeMediaId);

    List<PlaceMediaReportJpaEntity> findByPlaceMediaId(UUID placeMediaId);

    Page<PlaceMediaReportJpaEntity> findByStatusOrderByCreatedAtDescIdAsc(String status, Pageable pageable);

    Page<PlaceMediaReportJpaEntity> findAllByOrderByCreatedAtDescIdAsc(Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            SELECT r FROM PlaceMediaReportJpaEntity r
            WHERE (:status IS NULL OR r.status = :status)
              AND ((:origin = 'LEGACY' AND r.isLegacyDecision = true)
                OR (:origin = 'MODERATOR' AND r.isLegacyDecision = false AND r.status IN ('ACCEPTED', 'REJECTED'))
                OR (:origin = 'SYSTEM' AND r.status = 'AUTO_RESOLVED'))
            ORDER BY r.createdAt DESC, r.id ASC
            """)
    Page<PlaceMediaReportJpaEntity> findByStatusAndDecisionOrigin(
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("origin") String origin,
            Pageable pageable);
}

