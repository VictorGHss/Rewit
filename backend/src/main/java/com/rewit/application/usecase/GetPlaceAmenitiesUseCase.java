package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceAmenityDtos.PlaceAmenitiesView;
import com.rewit.application.port.PlaceAmenityRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceAmenityConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consulta das comodidades de um local do catálogo (F2.2), com a mesma regra de visibilidade do F2.1.
 *
 * <ul>
 *   <li>Local inexistente ou fora do catálogo público responde {@code 404 PLACE_NOT_FOUND};</li>
 *   <li>Sem configuração, ou com configuração de uma representação hoje inelegível (conta não aprovada,
 *       administrador inoperante, local desvinculado ou transferido), responde {@code configured = false} e lista
 *       vazia, sem expor seleções, conta comercial ou datas;</li>
 *   <li>A mesma representação, de novo elegível, volta a ter a configuração visível sem regravação. Uma nova
 *       titular nunca herda a configuração da anterior: precisa configurar a sua.</li>
 * </ul>
 */
@Service
public class GetPlaceAmenitiesUseCase {

    private final PlaceRepository placeRepository;
    private final PlaceAmenityRepository placeAmenityRepository;
    private final BusinessRepresentationPolicy representationPolicy;

    public GetPlaceAmenitiesUseCase(PlaceRepository placeRepository,
                                    PlaceAmenityRepository placeAmenityRepository,
                                    BusinessRepresentationPolicy representationPolicy) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeAmenityRepository = Objects.requireNonNull(placeAmenityRepository,
                "PlaceAmenityRepository must not be null");
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
    }

    @Transactional(readOnly = true)
    public PlaceAmenitiesView execute(UUID placeId) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }

        Place place = placeRepository.findById(placeId)
                .orElseThrow(GetPlaceAmenitiesUseCase::placeNotFound);
        if (!CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())) {
            throw placeNotFound();
        }

        Optional<PlaceAmenityConfiguration> configuration = placeAmenityRepository.findByPlaceId(placeId);
        if (configuration.isEmpty()) {
            return PlaceAmenitiesView.unconfigured(placeId);
        }
        if (!representationPolicy.currentIneligibility(configuration.get().getBusinessAccountId(), placeId)
                .isEmpty()) {
            return PlaceAmenitiesView.unconfigured(placeId);
        }
        return PlaceAmenitiesView.fromDomain(configuration.get());
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
