package com.rewit.application.dto.checkin;

import java.time.Instant;
import java.util.UUID;

/**
 * Visões de check-in da camada de aplicação. Nenhuma carrega coordenadas do usuário nem a distância medida.
 */
public final class CheckInDtos {

    private CheckInDtos() {
    }

    /** Check-in verificado do próprio usuário, com o local e a avaliação que o geraram. */
    public record MyCheckInView(
            UUID checkInId,
            UUID reviewId,
            UUID placeId,
            String placeName,
            String placeCategory,
            String placeCity,
            String placeState,
            Instant verifiedAt
    ) {}
}
