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

    List<PlaceMedia> findActiveByPlaceId(UUID placeId);

    long countActiveByPlaceId(UUID placeId);
}

