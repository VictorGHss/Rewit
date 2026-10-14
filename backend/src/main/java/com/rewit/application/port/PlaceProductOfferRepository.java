package com.rewit.application.port;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.PlaceProductOffer;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das ofertas comerciais de produtos (F2.3).
 */
public interface PlaceProductOfferRepository {

    Optional<PlaceProductOffer> findByPlaceIdAndProductId(UUID placeId, UUID productId);

    /** Insere ou atualiza pelo id e devolve o estado persistido. */
    PlaceProductOffer save(PlaceProductOffer offer);

    /** Apaga a oferta do produto no local se ela pertencer à conta; {@code false} se não havia. */
    boolean delete(UUID placeId, UUID productId, UUID businessAccountId);

    /**
     * Ofertas da conta no local cujo produto global está {@code ACTIVE}, por nome e id do produto. Não avalia a
     * elegibilidade da conta nem o status do local: isso é do caso de uso.
     */
    PageResult<PlaceProductOfferView> findListedByPlace(UUID placeId, UUID businessAccountId, int page, int size);
}
