package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.DeletePlaceProductOfferCommand;
import com.rewit.application.port.PlaceProductOfferRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Retira da vitrine a oferta de um produto num local reivindicado (F2.3). Mesma autorização e trava do PUT; não exige
 * produto {@code ACTIVE}, para permitir retirar a oferta de um produto desativado. Oferta inexistente responde
 * {@code 404 PLACE_PRODUCT_OFFER_NOT_FOUND}, avaliado só depois de o ator provar que representa o local. Nunca altera o
 * produto global nem a presença comunitária.
 */
@Service
public class DeletePlaceProductOfferUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final PlaceRepository placeRepository;
    private final PlaceProductOfferRepository offerRepository;

    public DeletePlaceProductOfferUseCase(BusinessRepresentationPolicy representationPolicy,
                                          PlaceRepository placeRepository,
                                          PlaceProductOfferRepository offerRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.offerRepository = Objects.requireNonNull(offerRepository, "PlaceProductOfferRepository must not be null");
    }

    @Transactional
    public void execute(DeletePlaceProductOfferCommand command) {
        Objects.requireNonNull(command, "DeletePlaceProductOfferCommand must not be null");
        OfferCommandChecks.requireIdentifiers(command.actorUserId(), command.placeId(), command.productId(),
                command.businessAccountId());

        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());
        Place place = OfferCommandChecks.lockRepresentedPlace(placeRepository, account, command.placeId());

        if (!offerRepository.delete(place.getId(), command.productId(), account.getId())) {
            throw new BusinessException("Oferta não encontrada", HttpStatus.NOT_FOUND, "PLACE_PRODUCT_OFFER_NOT_FOUND");
        }
    }
}
