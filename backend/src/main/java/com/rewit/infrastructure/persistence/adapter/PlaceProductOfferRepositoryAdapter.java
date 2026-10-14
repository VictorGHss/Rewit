package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.OfferedProductView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceProductOfferRepository;
import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.model.PlaceProductOffer;
import com.rewit.infrastructure.persistence.entity.PlaceProductOfferJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceProductOfferJpaRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência das ofertas comerciais (V28).
 */
@Component
public class PlaceProductOfferRepositoryAdapter implements PlaceProductOfferRepository {

    private final PlaceProductOfferJpaRepository repository;

    public PlaceProductOfferRepositoryAdapter(PlaceProductOfferJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "PlaceProductOfferJpaRepository must not be null");
    }

    @Override
    public Optional<PlaceProductOffer> findByPlaceIdAndProductId(UUID placeId, UUID productId) {
        if (placeId == null || productId == null) {
            return Optional.empty();
        }
        return repository.findByPlaceAndProduct(placeId, productId).map(PlaceProductOfferJpaEntity::toDomain);
    }

    @Override
    public PlaceProductOffer save(PlaceProductOffer offer) {
        Objects.requireNonNull(offer, "offer must not be null");
        PlaceProductOfferJpaEntity entity = repository.findById(offer.getId())
                .map(existing -> {
                    existing.updateFromDomain(offer);
                    return existing;
                })
                .orElseGet(() -> PlaceProductOfferJpaEntity.fromDomain(offer));
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public boolean delete(UUID placeId, UUID productId, UUID businessAccountId) {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(businessAccountId, "businessAccountId must not be null");
        return repository.deleteOffer(placeId, productId, businessAccountId) > 0;
    }

    @Override
    public PageResult<PlaceProductOfferView> findListedByPlace(UUID placeId, UUID businessAccountId, int page,
                                                               int size) {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(businessAccountId, "businessAccountId must not be null");
        List<PlaceProductOfferView> content = repository
                .findListedByPlace(placeId, businessAccountId, size, (long) page * size).stream()
                .map(PlaceProductOfferRepositoryAdapter::toView)
                .toList();
        return PageResult.of(content, page, size, repository.countListedByPlace(placeId, businessAccountId));
    }

    private static PlaceProductOfferView toView(Object[] row) {
        OfferedProductView product = new OfferedProductView((UUID) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4], (String) row[5]);
        BigDecimal price = row[7] == null ? null : ((BigDecimal) row[7]).setScale(2);
        return new PlaceProductOfferView(product, OfferAvailability.valueOf((String) row[6]), price, (String) row[8]);
    }
}
