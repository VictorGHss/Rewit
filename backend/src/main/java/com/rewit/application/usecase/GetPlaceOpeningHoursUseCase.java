package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.port.PlaceOpeningHoursRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Consulta pública dos horários de funcionamento de um local no catálogo.
 *
 * <p>Locais inativos ou inexistentes respondem 404 (indistinguíveis), conforme a política de visibilidade do catálogo.
 * Se o local não tiver horários cadastrados, retorna a visão com {@code configured = false}.
 */
@Service
public class GetPlaceOpeningHoursUseCase {

    private final PlaceRepository placeRepository;
    private final PlaceOpeningHoursRepository placeOpeningHoursRepository;

    public GetPlaceOpeningHoursUseCase(PlaceRepository placeRepository,
                                       PlaceOpeningHoursRepository placeOpeningHoursRepository) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeOpeningHoursRepository = Objects.requireNonNull(placeOpeningHoursRepository,
                "PlaceOpeningHoursRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PlaceOpeningHoursView execute(UUID placeId) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }

        Place place = placeRepository.findById(placeId)
                .orElseThrow(GetPlaceOpeningHoursUseCase::placeNotFound);

        if (!CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())) {
            throw placeNotFound();
        }

        return placeOpeningHoursRepository.findScheduleByPlaceId(placeId)
                .map(PlaceOpeningHoursView::fromDomain)
                .orElseGet(() -> PlaceOpeningHoursView.unconfigured(placeId));
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
