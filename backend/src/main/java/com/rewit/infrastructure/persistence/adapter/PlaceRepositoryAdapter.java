package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.catalog.CatalogDtos;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.infrastructure.persistence.entity.PlaceJpaEntity;
import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceJpaRepository;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import jakarta.persistence.Tuple;
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

    @Override
    public List<CatalogDtos.NearbyPlaceResult> findNearbyWithDistance(double latitude, double longitude, double radiusMeters, int limit) {
        if (radiusMeters <= 0 || limit <= 0) {
            return List.of();
        }

        List<Tuple> tuples = placeJpaRepository.findNearbyWithDistance(latitude, longitude, radiusMeters, limit);
        return tuples.stream()
                .map(PlaceRepositoryAdapter::toNearbyResult)
                .toList();
    }

    private static CatalogDtos.NearbyPlaceResult toNearbyResult(Tuple tuple) {
        Object idObj = tuple.get("id");
        UUID id = idObj instanceof UUID u ? u : UUID.fromString(idObj.toString());

        String name = tuple.get("name", String.class);
        String slug = tuple.get("slug", String.class);
        String category = tuple.get("category", String.class);
        String description = tuple.get("description", String.class);
        String addressText = tuple.get("address_text", String.class);
        String streetNumber = tuple.get("street_number", String.class);
        String neighborhood = tuple.get("neighborhood", String.class);
        String city = tuple.get("city", String.class);
        String state = tuple.get("state", String.class);
        String country = tuple.get("country", String.class);

        double latitude = ((Number) tuple.get("latitude")).doubleValue();
        double longitude = ((Number) tuple.get("longitude")).doubleValue();
        int validationRadiusMeters = ((Number) tuple.get("validation_radius_meters")).intValue();
        String origin = tuple.get("origin", String.class);
        boolean isVerified = Boolean.TRUE.equals(tuple.get("is_verified", Boolean.class));

        Object claimedObj = tuple.get("claimed_by_business_id");
        UUID claimedByBusinessId = claimedObj != null
                ? (claimedObj instanceof UUID u ? u : UUID.fromString(claimedObj.toString()))
                : null;

        String status = tuple.get("status", String.class);
        double distanceMeters = ((Number) tuple.get("distance_meters")).doubleValue();

        Place place = new Place(
                id,
                name,
                slug,
                category,
                description,
                addressText,
                streetNumber,
                neighborhood,
                city,
                state,
                country,
                latitude,
                longitude,
                validationRadiusMeters,
                origin,
                isVerified,
                claimedByBusinessId,
                status
        );

        return new CatalogDtos.NearbyPlaceResult(place, distanceMeters);
    }

    private static Place toDomain(PlaceJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
