package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceMediaReportRepository;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaReportJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @jakarta.persistence.PersistenceContext
    private final jakarta.persistence.EntityManager entityManager;

    public PlaceMediaReportRepositoryAdapter(PlaceMediaReportJpaRepository placeMediaReportJpaRepository,
                                             jakarta.persistence.EntityManager entityManager) {
        this.placeMediaReportJpaRepository = Objects.requireNonNull(placeMediaReportJpaRepository, "PlaceMediaReportJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
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
    @Transactional
    public Optional<PlaceMediaReport> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        Optional<PlaceMediaReportJpaEntity> entityOpt = placeMediaReportJpaRepository.findByIdForUpdate(id);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }
        PlaceMediaReportJpaEntity entity = entityOpt.get();
        if (entityManager == null) {
            throw new IllegalStateException("EntityManager é obrigatório para execução de lock pessimista e refresh");
        }
        try {
            entityManager.refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
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
    @Transactional
    public List<PlaceMediaReport> findByPlaceMediaIdForUpdate(UUID placeMediaId) {
        if (placeMediaId == null) {
            return List.of();
        }
        List<PlaceMediaReportJpaEntity> entities = placeMediaReportJpaRepository.findByPlaceMediaIdForUpdate(placeMediaId);
        if (entityManager == null) {
            throw new IllegalStateException("EntityManager é obrigatório para execução de lock pessimista e refresh");
        }
        for (PlaceMediaReportJpaEntity entity : entities) {
            try {
                entityManager.refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            } catch (jakarta.persistence.EntityNotFoundException ignored) {
            }
        }
        return entities.stream()
                .map(PlaceMediaReportJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PlaceMediaReport> findByStatusAndDecisionOrigin(String status, String decisionOrigin, int page, int size) {
        String normalizedStatus = normalizeStatusFilter(status);

        String normalizedOrigin = normalizeDecisionOriginFilter(decisionOrigin);

        Pageable pageable = PageRequest.of(page, size);
        Page<PlaceMediaReportJpaEntity> jpaPage;
        if (normalizedOrigin != null) {
            jpaPage = placeMediaReportJpaRepository.findByStatusAndDecisionOrigin(normalizedStatus, normalizedOrigin, pageable);
        } else if (normalizedStatus != null) {
            jpaPage = placeMediaReportJpaRepository.findByStatusOrderByCreatedAtDescIdAsc(normalizedStatus, pageable);
        } else {
            jpaPage = placeMediaReportJpaRepository.findAllByOrderByCreatedAtDescIdAsc(pageable);
        }

        List<PlaceMediaReport> content = jpaPage.getContent().stream()
                .map(PlaceMediaReportJpaEntity::toDomain)
                .toList();

        return PageResult.of(content, jpaPage.getNumber(), jpaPage.getSize(), jpaPage.getTotalElements());
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase()) {
            case "PENDING", "OPEN" -> "PENDING";
            case "ACCEPTED", "RESOLVED" -> "ACCEPTED";
            case "REJECTED", "DISMISSED" -> "REJECTED";
            case "AUTO_RESOLVED" -> "AUTO_RESOLVED";
            default -> throw new com.rewit.common.exception.BusinessException(
                    "Status de filtro de denúncia inválido: " + status,
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_REPORT_STATUS"
            );
        };
    }

    private static String normalizeDecisionOriginFilter(String decisionOrigin) {
        if (decisionOrigin == null || decisionOrigin.isBlank()) {
            return null;
        }
        return switch (decisionOrigin.trim().toUpperCase()) {
            case PlaceMediaReport.DECISION_ORIGIN_MODERATOR -> PlaceMediaReport.DECISION_ORIGIN_MODERATOR;
            case PlaceMediaReport.DECISION_ORIGIN_SYSTEM -> PlaceMediaReport.DECISION_ORIGIN_SYSTEM;
            case PlaceMediaReport.DECISION_ORIGIN_LEGACY -> PlaceMediaReport.DECISION_ORIGIN_LEGACY;
            default -> throw new com.rewit.common.exception.BusinessException(
                    "Origem de decisão de denúncia inválida: " + decisionOrigin,
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_DECISION_ORIGIN"
            );
        };
    }
}
