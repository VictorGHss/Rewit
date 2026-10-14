package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.DayScheduleCommand;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.UpdatePlaceOpeningHoursCommand;
import com.rewit.application.port.PlaceOpeningHoursRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceDaySchedule;
import com.rewit.domain.model.PlaceOpeningHoursSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuração e atualização dos horários semanais recorrentes de funcionamento de um local reivindicado (F2.1).
 *
 * <p>Regras de autorização e elegibilidade:
 * <ul>
 *   <li>Ator autenticado e com conta pessoal operacional ({@code 401 UNAUTHORIZED} / {@code 401 ACCOUNT_DISABLED});</li>
 *   <li>Conta comercial existente, administrada pelo ator ({@code 404 BUSINESS_ACCOUNT_NOT_FOUND} se de outro) e
 *       aprovada ({@code 409 BUSINESS_ACCOUNT_NOT_APPROVED});</li>
 *   <li>Estabelecimento ativo e atualmente reivindicado pela conta comercial informada
 *       ({@code 403 BUSINESS_NOT_RESPONSIBLE} se de outra ou desvinculado; {@code 404 PLACE_NOT_FOUND} se inexistente/inativo).</li>
 * </ul>
 *
 * <p>A concorrência é serializada travando a linha do local no banco de dados ({@code SELECT ... FOR UPDATE}).
 */
@Service
public class UpdatePlaceOpeningHoursUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final PlaceRepository placeRepository;
    private final PlaceOpeningHoursRepository placeOpeningHoursRepository;

    public UpdatePlaceOpeningHoursUseCase(
            BusinessRepresentationPolicy representationPolicy,
            PlaceRepository placeRepository,
            PlaceOpeningHoursRepository placeOpeningHoursRepository
    ) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeOpeningHoursRepository = Objects.requireNonNull(placeOpeningHoursRepository,
                "PlaceOpeningHoursRepository must not be null");
    }

    @Transactional
    public PlaceOpeningHoursView execute(UpdatePlaceOpeningHoursCommand command) {
        Objects.requireNonNull(command, "UpdatePlaceOpeningHoursCommand must not be null");

        if (command.actorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.placeId() == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (command.businessAccountId() == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_ACCOUNT_ID");
        }

        // 1. Valida ator operacional e conta comercial administrada e aprovada
        BusinessAccount account = representationPolicy.requireApprovedAccount(
                command.actorUserId(),
                command.businessAccountId()
        );

        // 2. Trava o local no banco para garantir serialização e consistência
        Place place = placeRepository.findByIdForUpdate(command.placeId())
                .orElseThrow(UpdatePlaceOpeningHoursUseCase::placeNotFound);

        if (!CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())) {
            throw placeNotFound();
        }

        // 3. Valida se a conta comercial realmente representa este local ativo
        if (!BusinessRepresentationPolicy.represents(account, place)) {
            throw BusinessRepresentationPolicy.notResponsible();
        }

        // 4. Mapeia e valida as regras de negócio de horários no domínio
        List<PlaceDaySchedule> daySchedules = command.days() != null
                ? command.days().stream().map(DayScheduleCommand::toDomain).toList()
                : Collections.emptyList();

        PlaceOpeningHoursSchedule schedule = PlaceOpeningHoursSchedule.create(
                place.getId(),
                account.getId(),
                command.actorUserId(),
                daySchedules
        );

        // 5. Persiste atomicamente
        placeOpeningHoursRepository.saveSchedule(schedule);

        return PlaceOpeningHoursView.fromDomain(schedule);
    }

    private static BusinessException placeNotFound() {
        return new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
    }
}
