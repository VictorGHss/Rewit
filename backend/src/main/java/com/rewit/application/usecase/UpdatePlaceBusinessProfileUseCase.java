package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceBusinessProfileDtos.UpdatePlaceBusinessProfileCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceBusinessProfile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Substituição integral do perfil comercial (descrição e contatos públicos) de um local reivindicado.
 *
 * <p>O perfil é validado inteiro antes de qualquer leitura ou escrita ({@code 400}). Depois valem as regras da Política
 * B, na mesma ordem do F2.1/F2.2/F2.3:
 * <ul>
 *   <li>ator autenticado e operacional ({@code 401 UNAUTHORIZED} / {@code 401 ACCOUNT_DISABLED});</li>
 *   <li>conta comercial do ator ({@code 404 BUSINESS_ACCOUNT_NOT_FOUND}) e aprovada
 *       ({@code 409 BUSINESS_ACCOUNT_NOT_APPROVED});</li>
 *   <li>local ativo ({@code 404 PLACE_NOT_FOUND}) e reivindicado hoje pela conta informada
 *       ({@code 403 BUSINESS_NOT_RESPONSIBLE}).</li>
 * </ul>
 *
 * <p>A linha do local é travada ({@code findByIdForUpdate}) antes de conferir a representação e gravar: uma troca de
 * titular concorrente termina antes, e a conta que deixou de representar o local recebe {@code 403} sem gravar. A
 * gravação toca só descrição e contatos.
 */
@Service
public class UpdatePlaceBusinessProfileUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final PlaceRepository placeRepository;

    public UpdatePlaceBusinessProfileUseCase(BusinessRepresentationPolicy representationPolicy,
                                             PlaceRepository placeRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
    }

    @Transactional
    public Place execute(UpdatePlaceBusinessProfileCommand command) {
        Objects.requireNonNull(command, "UpdatePlaceBusinessProfileCommand must not be null");
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
        PlaceBusinessProfile profile = PlaceBusinessProfile.of(command.description(), command.phone(),
                command.websiteUrl(), command.instagramUrl(), command.facebookUrl());

        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());

        Place place = placeRepository.findByIdForUpdate(command.placeId())
                .orElseThrow(UpdatePlaceBusinessProfileUseCase::placeNotFound);
        if (!CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())) {
            throw placeNotFound();
        }
        if (!BusinessRepresentationPolicy.represents(account, place)) {
            throw BusinessRepresentationPolicy.notResponsible();
        }

        return placeRepository.updateBusinessProfile(place.getId(), profile)
                .orElseThrow(UpdatePlaceBusinessProfileUseCase::placeNotFound);
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
