package com.rewit.application.dto.catalog;

import java.util.UUID;

/**
 * DTOs da camada de aplicação para o perfil comercial (descrição e contatos) de um local reivindicado.
 */
public final class PlaceBusinessProfileDtos {

    private PlaceBusinessProfileDtos() {
    }

    /** Substituição integral do perfil: campo {@code null} ou em branco fica sem valor. */
    public record UpdatePlaceBusinessProfileCommand(
            UUID actorUserId,
            UUID placeId,
            UUID businessAccountId,
            String description,
            String phone,
            String websiteUrl,
            String instagramUrl,
            String facebookUrl
    ) {
    }
}
