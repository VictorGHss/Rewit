package com.rewit.presentation.dto.checkin;

import com.rewit.application.dto.checkin.CheckInDtos.MyCheckInView;

import java.time.Instant;
import java.util.UUID;

/**
 * Check-in verificado do próprio usuário em {@code GET /api/v1/me/check-ins}: local, data da verificação e avaliação
 * de origem. Sem coordenadas do usuário e sem a distância medida.
 */
public record MyCheckInResponse(
        UUID id,
        UUID reviewId,
        UUID placeId,
        String placeName,
        String placeCategory,
        String placeCity,
        String placeState,
        Instant verifiedAt
) {

    public static MyCheckInResponse fromView(MyCheckInView view) {
        return new MyCheckInResponse(
                view.checkInId(),
                view.reviewId(),
                view.placeId(),
                view.placeName(),
                view.placeCategory(),
                view.placeCity(),
                view.placeState(),
                view.verifiedAt()
        );
    }
}
