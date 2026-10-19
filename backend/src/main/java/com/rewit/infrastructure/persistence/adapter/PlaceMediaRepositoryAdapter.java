package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para PlaceMediaRepository implementado com Spring Data JPA.
 */
@Component
public class PlaceMediaRepositoryAdapter implements PlaceMediaRepository {

    private final PlaceMediaJpaRepository placeMediaJpaRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    public PlaceMediaRepositoryAdapter(PlaceMediaJpaRepository placeMediaJpaRepository) {
        this.placeMediaJpaRepository = Objects.requireNonNull(placeMediaJpaRepository, "PlaceMediaJpaRepository must not be null");
    }

    public PlaceMediaRepositoryAdapter(PlaceMediaJpaRepository placeMediaJpaRepository,
                                       jakarta.persistence.EntityManager entityManager) {
        this.placeMediaJpaRepository = Objects.requireNonNull(placeMediaJpaRepository, "PlaceMediaJpaRepository must not be null");
        this.entityManager = entityManager;
    }

    @Override
    public PlaceMedia save(PlaceMedia media) {
        Objects.requireNonNull(media, "PlaceMedia cannot be null");
        PlaceMediaJpaEntity entity = PlaceMediaJpaEntity.fromDomain(media);
        PlaceMediaJpaEntity saved = placeMediaJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<PlaceMedia> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findById(id)
                .map(PlaceMediaJpaEntity::toDomain);
    }

    @Override
    public Optional<PlaceMedia> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        Optional<PlaceMediaJpaEntity> entityOpt = placeMediaJpaRepository.findByIdForUpdate(id);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }
        PlaceMediaJpaEntity entity = entityOpt.get();
        if (entityManager != null) {
            entityManager.refresh(entity);
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public List<PlaceMedia> findActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return placeMediaJpaRepository.findByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(placeId, PlaceMediaStatus.ACTIVE.name())
                .stream()
                .map(PlaceMediaJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return 0;
        }
        return placeMediaJpaRepository.countByPlaceIdAndStatus(placeId, PlaceMediaStatus.ACTIVE.name());
    }

    @Override
    public Optional<PlaceMedia> findPrimaryByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findByPlaceIdAndIsPrimaryTrueAndStatus(placeId, PlaceMediaStatus.ACTIVE.name())
                .map(PlaceMediaJpaEntity::toDomain);
    }

    @Override
    public Optional<PlaceMedia> findFirstActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findFirstByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(placeId, PlaceMediaStatus.ACTIVE.name())
                .map(PlaceMediaJpaEntity::toDomain);
    }

    @Override
    public Optional<PlaceMedia> findByIdempotencyKey(UUID userId, UUID placeId, String idempotencyKey) {
        if (userId == null || placeId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findByUserIdAndPlaceIdAndIdempotencyKey(userId, placeId, idempotencyKey.trim())
                .map(PlaceMediaJpaEntity::toDomain);
    }

    @Override
    public List<PlaceMedia> findByObjectKeyIn(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return List.of();
        }
        return placeMediaJpaRepository.findByObjectKeyIn(objectKeys)
                .stream()
                .map(PlaceMediaJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<PlaceMedia> findRemovalsOlderThan(Instant cutoff, int limit) {
        if (cutoff == null || limit <= 0) {
            return List.of();
        }
        return placeMediaJpaRepository.findRemovalsOlderThan(cutoff, PageRequest.of(0, limit))
                .stream()
                .map(PlaceMediaJpaEntity::toDomain)
                .toList();
    }

    @Override
    public com.rewit.application.dto.common.PageResult<PlaceMedia> findByStatus(PlaceMediaStatus status, int page, int size) {
        if (status == null) {
            return com.rewit.application.dto.common.PageResult.of(List.of(), page, size, 0);
        }
        org.springframework.data.domain.Pageable pageable = PageRequest.of(page, size);
        org.springframework.data.domain.Page<PlaceMediaJpaEntity> jpaPage =
                placeMediaJpaRepository.findByStatusOrderByCreatedAtAscIdAsc(status.name(), pageable);
        List<PlaceMedia> content = jpaPage.getContent().stream()
                .map(PlaceMediaJpaEntity::toDomain)
                .toList();
        return com.rewit.application.dto.common.PageResult.of(content, jpaPage.getNumber(), jpaPage.getSize(), jpaPage.getTotalElements());
    }
}

