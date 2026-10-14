package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceProductOfferRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Vitrine de ofertas comerciais de um local (F2.3).
 *
 * <ul>
 *   <li>Local inexistente ou fora do catálogo público responde {@code 404 PLACE_NOT_FOUND};</li>
 *   <li>só aparecem ofertas da conta titular atual e com produto global {@code ACTIVE};</li>
 *   <li>titular hoje inelegível (conta não aprovada, administrador inoperante) ou local sem titular responde página
 *       vazia, sem alterar o banco: a mesma conta, elegível de novo, volta a ter as ofertas visíveis;</li>
 *   <li>ofertas de titulares anteriores não existem mais: a V28 as apaga na troca de titular (T2).</li>
 * </ul>
 */
@Service
public class ListPlaceProductOffersUseCase {

    public static final int MAX_PAGE_SIZE = 50;

    private final PlaceRepository placeRepository;
    private final PlaceProductOfferRepository offerRepository;
    private final BusinessRepresentationPolicy representationPolicy;

    public ListPlaceProductOffersUseCase(PlaceRepository placeRepository, PlaceProductOfferRepository offerRepository,
                                         BusinessRepresentationPolicy representationPolicy) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.offerRepository = Objects.requireNonNull(offerRepository, "PlaceProductOfferRepository must not be null");
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<PlaceProductOfferView> execute(UUID placeId, int page, int size) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 50", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE");
        }

        Place place = placeRepository.findById(placeId)
                .filter(found -> CatalogService.PUBLIC_CATALOG_STATUS.equals(found.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND,
                        "PLACE_NOT_FOUND"));

        UUID owner = place.getClaimedByBusinessId();
        if (owner == null || !representationPolicy.currentIneligibility(owner, placeId).isEmpty()) {
            return PageResult.of(List.of(), page, size, 0L);
        }
        return offerRepository.findListedByPlace(placeId, owner, page, size);
    }
}
