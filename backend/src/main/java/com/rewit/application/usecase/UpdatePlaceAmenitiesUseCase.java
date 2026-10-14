package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceAmenityDtos.PlaceAmenitiesView;
import com.rewit.application.dto.catalog.PlaceAmenityDtos.UpdatePlaceAmenitiesCommand;
import com.rewit.application.port.PlaceAmenityRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceAmenity;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceAmenityConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Substituição integral das comodidades de um local reivindicado (F2.2).
 *
 * <p>A lista é validada inteira antes de qualquer leitura ou escrita: código fora do catálogo, elemento nulo ou
 * repetição respondem {@code 400} sem tocar no banco. Depois valem as regras da Política B, na mesma ordem do F2.1:
 * <ul>
 *   <li>ator autenticado e operacional ({@code 401 UNAUTHORIZED} / {@code 401 ACCOUNT_DISABLED});</li>
 *   <li>conta comercial do ator ({@code 404 BUSINESS_ACCOUNT_NOT_FOUND}) e aprovada
 *       ({@code 409 BUSINESS_ACCOUNT_NOT_APPROVED});</li>
 *   <li>local ativo ({@code 404 PLACE_NOT_FOUND}) e reivindicado hoje pela conta informada
 *       ({@code 403 BUSINESS_NOT_RESPONSIBLE}).</li>
 * </ul>
 *
 * <p>A linha do local é travada ({@code findByIdForUpdate}) antes de conferir a representação e gravar, serializando
 * gravações concorrentes com reivindicações, transferências e outras configurações do mesmo local.
 */
@Service
public class UpdatePlaceAmenitiesUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final PlaceRepository placeRepository;
    private final PlaceAmenityRepository placeAmenityRepository;

    public UpdatePlaceAmenitiesUseCase(BusinessRepresentationPolicy representationPolicy,
                                       PlaceRepository placeRepository,
                                       PlaceAmenityRepository placeAmenityRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeAmenityRepository = Objects.requireNonNull(placeAmenityRepository,
                "PlaceAmenityRepository must not be null");
    }

    @Transactional
    public PlaceAmenitiesView execute(UpdatePlaceAmenitiesCommand command) {
        Objects.requireNonNull(command, "UpdatePlaceAmenitiesCommand must not be null");
        if (command.actorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.placeId() == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (command.businessAccountId() == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_BUSINESS_ACCOUNT_ID");
        }
        List<PlaceAmenity> amenities = PlaceAmenityConfiguration.fromCodes(command.amenities());

        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());

        Place place = placeRepository.findByIdForUpdate(command.placeId())
                .orElseThrow(UpdatePlaceAmenitiesUseCase::placeNotFound);
        if (!CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())) {
            throw placeNotFound();
        }
        if (!BusinessRepresentationPolicy.represents(account, place)) {
            throw BusinessRepresentationPolicy.notResponsible();
        }

        PlaceAmenityConfiguration saved = placeAmenityRepository.save(PlaceAmenityConfiguration.create(
                place.getId(), account.getId(), command.actorUserId(), amenities,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return PlaceAmenitiesView.fromDomain(saved);
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
