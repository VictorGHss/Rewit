package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceMediaReportRepository;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaReportJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para PlaceMediaReportRepository implementado com Spring Data JPA.
 */
@Component
public class PlaceMediaReportRepositoryAdapter implements PlaceMediaReportRepository {

    private final PlaceMediaReportJpaRepository placeMediaReportJpaRepository;

    public PlaceMediaReportRepositoryAdapter(PlaceMediaReportJpaRepository placeMediaReportJpaRepository) {
        this.placeMediaReportJpaRepository = Objects.requireNonNull(placeMediaReportJpaRepository, "PlaceMediaReportJpaRepository must not be null");
    }

    @Override
    @Transactional
    public PlaceMediaReport save(PlaceMediaReport report) {
        Objects.requireNonNull(report, "PlaceMediaReport cannot be null");
        PlaceMediaReportJpaEntity entity = PlaceMediaReportJpaEntity.fromDomain(report);
        PlaceMediaReportJpaEntity saved = placeMediaReportJpaRepository.save(entity);
        if (saved == null) {
            return report;
        }
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlaceMediaReport> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return placeMediaReportJpaRepository.findById(id)
                .map(PlaceMediaReportJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByPlaceMediaIdAndReporterUserId(UUID placeMediaId, UUID reporterUserId) {
        if (placeMediaId == null || reporterUserId == null) {
            return false;
        }
        return placeMediaReportJpaRepository.existsByPlaceMediaIdAndReporterUserId(placeMediaId, reporterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPlaceMediaIdAndStatus(UUID placeMediaId, String status) {
        if (placeMediaId == null || status == null || status.isBlank()) {
            return 0;
        }
        return placeMediaReportJpaRepository.countByPlaceMediaIdAndStatus(placeMediaId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlaceMediaReport> findByPlaceMediaId(UUID placeMediaId) {
        if (placeMediaId == null) {
            return List.of();
        }
        return placeMediaReportJpaRepository.findByPlaceMediaId(placeMediaId)
                .stream()
                .map(PlaceMediaReportJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PlaceMediaReport> findByStatus(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<PlaceMediaReportJpaEntity> jpaPage;
        if (status != null && !status.isBlank()) {
            jpaPage = placeMediaReportJpaRepository.findByStatusOrderByCreatedAtAsc(status.trim().toUpperCase(), pageable);
        } else {
            jpaPage = placeMediaReportJpaRepository.findAll(pageable);
        }

        List<PlaceMediaReport> content = jpaPage.getContent().stream()
                .map(PlaceMediaReportJpaEntity::toDomain)
                .toList();

        return PageResult.of(content, jpaPage.getNumber(), jpaPage.getSize(), jpaPage.getTotalElements());
    }
}

