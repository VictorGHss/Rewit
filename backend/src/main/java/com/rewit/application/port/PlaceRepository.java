package com.rewit.application.port;

import com.rewit.domain.model.Place;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação de locais físicos (Place).
 */
public interface PlaceRepository {

    Place save(Place place);

    Optional<Place> findById(UUID id);

    /** Trava a linha do local (FOR UPDATE) e recarrega o estado confirmado; serializa as reivindicações do local. */
    Optional<Place> findByIdForUpdate(UUID id);

    /**
     * Vincula o local à conta comercial somente se ainda não estiver vinculado (UPDATE condicional).
     *
     * @return false se o local não existe ou já pertence a uma conta
     */
    boolean assignClaimedBusiness(UUID placeId, UUID businessAccountId);

    Optional<Place> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Place> findNearby(double latitude, double longitude, double radiusMeters);

    List<com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult> findNearbyWithDistance(
            double latitude,
            double longitude,
            double radiusMeters,
            int limit
    );

    Optional<com.rewit.application.dto.catalog.CatalogDtos.SpatialValidationResult> validateProximity(
            UUID placeId,
            double latitude,
            double longitude
    );
}
