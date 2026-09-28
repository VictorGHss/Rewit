package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.infrastructure.persistence.entity.PlaceJpaEntity;
import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceJpaRepository;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade Place.
 * Garante a integridade física com a raiz RateableTarget conforme ADR-009 e triggers do PostgreSQL.
 */
@Component
public class PlaceRepositoryAdapter implements PlaceRepository {

    private final PlaceJpaRepository placeJpaRepository;
    private final RateableTargetJpaRepository rateableTargetJpaRepository;

    public PlaceRepositoryAdapter(PlaceJpaRepository placeJpaRepository,
                                  RateableTargetJpaRepository rateableTargetJpaRepository) {
        this.placeJpaRepository = Objects.requireNonNull(placeJpaRepository, "PlaceJpaRepository must not be null");
        this.rateableTargetJpaRepository = Objects.requireNonNull(rateableTargetJpaRepository, "RateableTargetJpaRepository must not be null");
    }

    @Override
    @Transactional
    public Place save(Place place) {
        Objects.requireNonNull(place, "Place cannot be null");

        // 1. Assegura a existência do RateableTarget raiz associado antes de persistir o Place
        if (!rateableTargetJpaRepository.existsById(place.getId())) {
            RateableTargetJpaEntity target = new RateableTargetJpaEntity(
                    place.getId(),
                    TargetType.PLACE,
                    place.getCreatedAt()
            );
            rateableTargetJpaRepository.saveAndFlush(target);
        }

        // 2. Persiste ou atualiza o Place especializado
        return placeJpaRepository.findById(place.getId())
                .map(existing -> {
                    existing.updateFromDomain(place);
                    PlaceJpaEntity saved = placeJpaRepository.saveAndFlush(existing);
                    return Objects.requireNonNull(saved, "Saved PlaceJpaEntity cannot be null").toDomain();
                })
                .orElseGet(() -> {
                    PlaceJpaEntity newEntity = PlaceJpaEntity.fromDomain(place);
                    PlaceJpaEntity saved = placeJpaRepository.saveAndFlush(newEntity);
                    return Objects.requireNonNull(saved, "Saved PlaceJpaEntity cannot be null").toDomain();
                });
    }

    @Override
    public Optional<Place> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return placeJpaRepository.findById(id).map(PlaceRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Place> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return placeJpaRepository.findBySlug(slug.trim().toLowerCase()).map(PlaceRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return placeJpaRepository.existsBySlug(slug.trim().toLowerCase());
    }

    @Override
    public List<Place> findNearby(double latitude, double longitude, double radiusMeters) {
        if (radiusMeters <= 0) {
            return List.of();
        }
        return placeJpaRepository.findNearby(latitude, longitude, radiusMeters).stream()
                .map(PlaceRepositoryAdapter::toDomain)
                .toList();
    }

    private static Place toDomain(PlaceJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
