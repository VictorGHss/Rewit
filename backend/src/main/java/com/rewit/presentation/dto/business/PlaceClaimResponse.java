package com.rewit.presentation.dto.business;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.domain.enums.PlaceClaimStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação HTTP de solicitação de reivindicação de local (C9).
 * Nunca expõe o userId do proprietário da conta nem o identificador do moderador que decidiu.
 */
public record PlaceClaimResponse(
        UUID id,
        UUID businessAccountId,
        String corporateName,
        String taxId,
        UUID placeId,
        String placeName,
        String city,
        String state,
        PlaceClaimStatus status,
        String evidenceDescription,
        Instant createdAt,
        Instant decidedAt,
        String decisionReason
) {
    public static PlaceClaimResponse fromView(PlaceClaimView view) {
        return new PlaceClaimResponse(
                view.id(),
                view.businessAccountId(),
                view.corporateName(),
                view.taxId(),
                view.placeId(),
                view.placeName(),
                view.city(),
                view.state(),
                view.status(),
                view.evidenceDescription(),
                view.createdAt(),
                view.decidedAt(),
                view.decisionReason()
        );
    }
}
