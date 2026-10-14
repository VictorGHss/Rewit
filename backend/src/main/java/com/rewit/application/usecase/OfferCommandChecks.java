package com.rewit.application.usecase;

import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Passos comuns às escritas de ofertas (F2.3), na mesma ordem do F2.1/F2.2. */
final class OfferCommandChecks {

    private OfferCommandChecks() {
    }

    static void requireIdentifiers(UUID actorUserId, UUID placeId, UUID productId, UUID businessAccountId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (productId == null) {
            throw new BusinessException("O produto é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PRODUCT_ID");
        }
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_BUSINESS_ACCOUNT_ID");
        }
    }

    /** Trava o local; {@code 404} se inexistente ou fora do catálogo, {@code 403} se a conta não o representa. */
    static Place lockRepresentedPlace(PlaceRepository placeRepository, BusinessAccount account, UUID placeId) {
        Place place = placeRepository.findByIdForUpdate(placeId)
                .filter(found -> CatalogService.PUBLIC_CATALOG_STATUS.equals(found.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND,
                        "PLACE_NOT_FOUND"));
        if (!BusinessRepresentationPolicy.represents(account, place)) {
            throw BusinessRepresentationPolicy.notResponsible();
        }
        return place;
    }
}
