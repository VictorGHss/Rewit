package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.UpsertPlaceProductOfferCommand;
import com.rewit.application.port.PlaceProductOfferRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceProductOffer;
import com.rewit.domain.model.Product;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cria ou atualiza a oferta de um produto global num local reivindicado (F2.3, PUT idempotente).
 *
 * <p>Disponibilidade, preço e moeda são validados antes de qualquer leitura ou escrita. Depois, na ordem do F2.1/F2.2:
 * conta administrada pelo ator e aprovada (Política B), trava do local ({@code findByIdForUpdate}), local
 * {@code ACTIVE} ({@code 404}) e reivindicado agora pela conta ({@code 403}), e produto global existente e
 * {@code ACTIVE} ({@code 404 PRODUCT_NOT_FOUND}). O produto global e a presença comunitária nunca são alterados.
 *
 * <p>A trava do local serializa esta gravação com outras ofertas do mesmo local e com a troca de titular, que apaga
 * as ofertas anteriores (V28): se a troca vier antes, a conta deixa de representar o local e recebe {@code 403}; se
 * vier depois, apaga também a oferta recém-gravada.
 */
@Service
public class UpsertPlaceProductOfferUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final PlaceRepository placeRepository;
    private final ProductRepository productRepository;
    private final PlaceProductOfferRepository offerRepository;

    public UpsertPlaceProductOfferUseCase(BusinessRepresentationPolicy representationPolicy,
                                          PlaceRepository placeRepository,
                                          ProductRepository productRepository,
                                          PlaceProductOfferRepository offerRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository must not be null");
        this.offerRepository = Objects.requireNonNull(offerRepository, "PlaceProductOfferRepository must not be null");
    }

    @Transactional
    public PlaceProductOfferView execute(UpsertPlaceProductOfferCommand command) {
        Objects.requireNonNull(command, "UpsertPlaceProductOfferCommand must not be null");
        OfferCommandChecks.requireIdentifiers(command.actorUserId(), command.placeId(), command.productId(),
                command.businessAccountId());
        OfferAvailability availability = OfferAvailability.fromCode(command.availability());
        BigDecimal price = PlaceProductOffer.normalizePrice(command.price());
        String currency = PlaceProductOffer.normalizeCurrency(command.currency());

        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());
        Place place = OfferCommandChecks.lockRepresentedPlace(placeRepository, account, command.placeId());
        Product product = productRepository.findById(command.productId())
                .filter(found -> CatalogService.PUBLIC_CATALOG_STATUS.equals(found.getStatus()))
                .orElseThrow(() -> new BusinessException("Produto não encontrado", HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<PlaceProductOffer> existing = offerRepository.findByPlaceIdAndProductId(place.getId(),
                product.getId());
        PlaceProductOffer offer;
        if (existing.isEmpty()) {
            offer = offerRepository.save(PlaceProductOffer.create(place.getId(), product.getId(), account.getId(),
                    command.actorUserId(), availability, price, currency, now));
        } else if (existing.get().hasTerms(account.getId(), availability, price, currency)) {
            offer = existing.get();
        } else {
            offer = offerRepository.save(existing.get().revise(account.getId(), command.actorUserId(), availability,
                    price, currency, now));
        }
        return PlaceProductOfferView.of(offer, product);
    }
}
