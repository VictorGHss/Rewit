package com.rewit.application.port;

import com.rewit.domain.model.PlaceAmenityConfiguration;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das comodidades de locais reivindicados (F2.2).
 */
public interface PlaceAmenityRepository {

    /** Configuração gravada para o local; vazio quando nenhuma representação configurou comodidades. */
    Optional<PlaceAmenityConfiguration> findByPlaceId(UUID placeId);

    /**
     * Grava a configuração substituindo integralmente a seleção anterior. Preserva {@code createdAt} de uma
     * configuração existente e devolve o estado persistido.
     */
    PlaceAmenityConfiguration save(PlaceAmenityConfiguration configuration);
}
