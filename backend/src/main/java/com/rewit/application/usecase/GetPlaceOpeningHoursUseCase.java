package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.port.PlaceOpeningHoursRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceOpeningHoursSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consulta pública dos horários de funcionamento de um local no catálogo.
 *
 * <p>Regras de visibilidade e elegibilidade (Decisão F2):
 * <ul>
 *   <li>Locais inativos ou inexistentes respondem 404 (indistinguíveis), conforme a política de visibilidade do catálogo;</li>
 *   <li>Se o local não tiver horários cadastrados, retorna visão com {@code configured = false} e {@code days = []};</li>
 *   <li>Se o local possuir horários cadastrados, mas a representação comercial titular estiver temporariamente inelegível
 *       (conta não aprovada, administrador inoperante ou estabelecimento desvinculado/transferido), a configuração fica oculta,
 *       retornando {@code configured = false} e {@code days = []} sem expor dados parciais ou metadados;</li>
 *   <li>Se a mesma representação voltar a ser elegível, a configuração volta a ser visível automaticamente sem mutações de banco;</li>
 *   <li>Se o local for transferido para outra conta comercial, os horários antigos de outrem não são apresentados como válidos.</li>
 * </ul>
 */
@Service
public class GetPlaceOpeningHoursUseCase {

    private final PlaceRepository placeRepository;
    private final PlaceOpeningHoursRepository placeOpeningHoursRepository;
    private final BusinessRepresentationPolicy representationPolicy;

    public GetPlaceOpeningHoursUseCase(PlaceRepository placeRepository,
                                       PlaceOpeningHoursRepository placeOpeningHoursRepository,
                                       BusinessRepresentationPolicy representationPolicy) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeOpeningHoursRepository = Objects.requireNonNull(placeOpeningHoursRepository,
                "PlaceOpeningHoursRepository must not be null");
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
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

        Optional<PlaceOpeningHoursSchedule> scheduleOpt = placeOpeningHoursRepository.findScheduleByPlaceId(placeId);
        if (scheduleOpt.isEmpty()) {
            return PlaceOpeningHoursView.unconfigured(placeId);
        }

        PlaceOpeningHoursSchedule schedule = scheduleOpt.get();

        // Avalia se a representação comercial que cadastrou os horários é atualmente elegível e responsável pelo local
        boolean isEligible = representationPolicy.currentIneligibility(schedule.getBusinessAccountId(), placeId).isEmpty();
        if (!isEligible) {
            return PlaceOpeningHoursView.unconfigured(placeId);
        }

        return PlaceOpeningHoursView.fromDomain(schedule);
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
