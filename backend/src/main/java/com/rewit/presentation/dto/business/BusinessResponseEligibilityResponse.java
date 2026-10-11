package com.rewit.presentation.dto.business;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseEligibilityView;
import com.rewit.application.dto.business.BusinessResponseDtos.EligibleBusinessAccount;
import com.rewit.application.dto.business.BusinessResponseDtos.EligiblePlace;

import java.util.List;
import java.util.UUID;

/**
 * Resposta da consulta de elegibilidade para responder oficialmente a uma avaliação (C11).
 *
 * <p>Apresenta apenas as contas aprovadas administradas pelo usuário autenticado que controlam
 * locais associados à avaliação, preservando a escolha do local representado.
 * Não expõe dados fiscais ({@code taxId}) nem contas de terceiros.
 */
public record BusinessResponseEligibilityResponse(
        UUID reviewId,
        boolean respondable,
        List<EligibleBusinessAccountResponse> accounts
) {
    public static BusinessResponseEligibilityResponse fromView(BusinessResponseEligibilityView view) {
        if (view == null) {
            return null;
        }
        List<EligibleBusinessAccountResponse> accounts = view.accounts() == null ? List.of() :
                view.accounts().stream()
                        .map(EligibleBusinessAccountResponse::fromView)
                        .toList();
        return new BusinessResponseEligibilityResponse(
                view.reviewId(),
                view.respondable(),
                accounts
        );
    }

    public record EligibleBusinessAccountResponse(
            UUID businessAccountId,
            String corporateName,
            List<EligiblePlaceResponse> places,
            UUID activeResponseId,
            boolean canPublish
    ) {
        public static EligibleBusinessAccountResponse fromView(EligibleBusinessAccount view) {
            if (view == null) {
                return null;
            }
            List<EligiblePlaceResponse> places = view.places() == null ? List.of() :
                    view.places().stream()
                            .map(EligiblePlaceResponse::fromView)
                            .toList();
            return new EligibleBusinessAccountResponse(
                    view.businessAccountId(),
                    view.corporateName(),
                    places,
                    view.activeResponseId(),
                    view.canPublish()
            );
        }
    }

    public record EligiblePlaceResponse(
            UUID placeId,
            String placeName
    ) {
        public static EligiblePlaceResponse fromView(EligiblePlace view) {
            if (view == null) {
                return null;
            }
            return new EligiblePlaceResponse(view.placeId(), view.placeName());
        }
    }
}
