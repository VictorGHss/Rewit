package com.rewit.application.port;

import com.rewit.domain.model.PlaceMedia;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída para persistência e recuperação de mídias vinculadas a locais (places).
 */
public interface PlaceMediaRepository {

    PlaceMedia save(PlaceMedia media);

    Optional<PlaceMedia> findById(UUID id);

    default Optional<PlaceMedia> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    List<PlaceMedia> findActiveByPlaceId(UUID placeId);

    long countActiveByPlaceId(UUID placeId);

    Optional<PlaceMedia> findPrimaryByPlaceId(UUID placeId);

    Optional<PlaceMedia> findFirstActiveByPlaceId(UUID placeId);

    Optional<PlaceMedia> findByIdempotencyKey(UUID userId, UUID placeId, String idempotencyKey);

    List<PlaceMedia> findByObjectKeyIn(List<String> objectKeys);

    default List<PlaceMedia> findRemovalsOlderThan(java.time.Instant cutoff, int limit) {
        return java.util.List.of();
    }

    default com.rewit.application.dto.common.PageResult<PlaceMedia> findByStatus(com.rewit.domain.enums.PlaceMediaStatus status, int page, int size) {
        return com.rewit.application.dto.common.PageResult.of(java.util.List.of(), page, size, 0);
    }
}

