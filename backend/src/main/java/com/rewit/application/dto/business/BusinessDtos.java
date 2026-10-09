package com.rewit.application.dto.business;

import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceClaimRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Visões de aplicação de contas comerciais e reivindicações de locais (C9). O documento fiscal só deve chegar ao
 * administrador da própria conta ou à fila administrativa. Nenhuma visão expõe o usuário administrador da conta nem
 * quem decidiu a solicitação.
 */
public final class BusinessDtos {

    private BusinessDtos() {
    }

    public record BusinessAccountView(
            UUID id,
            String corporateName,
            String taxId,
            VerificationStatus verificationStatus,
            String planTier,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static BusinessAccountView fromDomain(BusinessAccount account) {
            return new BusinessAccountView(account.getId(), account.getCorporateName(), account.getTaxId(),
                    account.getVerificationStatus(), account.getPlanTier(), account.getCreatedAt(), account.getUpdatedAt());
        }
    }

    public record PlaceClaimView(
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
        public static PlaceClaimView of(PlaceClaimRequest claim, BusinessAccount account, Place place) {
            return new PlaceClaimView(claim.getId(), account.getId(), account.getCorporateName(), account.getTaxId(),
                    place.getId(), place.getName(), place.getCity(), place.getState(), claim.getStatus(),
                    claim.getEvidenceDescription(), claim.getCreatedAt(), claim.getDecidedAt(), claim.getDecisionReason());
        }
    }
}
