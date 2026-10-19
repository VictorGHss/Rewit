package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaJpaRepository;
import org.springframework.stereotype.Component;

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

    public PlaceMediaRepositoryAdapter(PlaceMediaJpaRepository placeMediaJpaRepository) {
        this.placeMediaJpaRepository = Objects.requireNonNull(placeMediaJpaRepository, "PlaceMediaJpaRepository must not be null");
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
    public List<PlaceMedia> findActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return placeMediaJpaRepository.findByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(placeId, ReviewMediaStatus.ACTIVE.name())
                .stream()
                .map(PlaceMediaJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return 0;
        }
        return placeMediaJpaRepository.countByPlaceIdAndStatus(placeId, ReviewMediaStatus.ACTIVE.name());
    }

    @Override
    public Optional<PlaceMedia> findPrimaryByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findByPlaceIdAndIsPrimaryTrueAndStatus(placeId, ReviewMediaStatus.ACTIVE.name())
                .map(PlaceMediaJpaEntity::toDomain);
    }

    @Override
    public Optional<PlaceMedia> findFirstActiveByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return placeMediaJpaRepository.findFirstByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(placeId, ReviewMediaStatus.ACTIVE.name())
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
}

