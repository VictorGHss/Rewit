package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Fila administrativa de reivindicações (C9): mais antigas primeiro, filtro opcional por status.
 *
 * <p>A rota HTTP filtra pela role do JWT; aqui a role atual é revalidada no banco (ModeratorRolePolicy), como na
 * decisão: a fila expõe documentos fiscais, e um moderador rebaixado não a consulta com um token antigo.
 */
@Service
public class QueryAdminPlaceClaimsUseCase {

    private final PlaceClaimRequestRepository placeClaimRequestRepository;
    private final ModeratorRolePolicy moderatorRolePolicy;

    public QueryAdminPlaceClaimsUseCase(PlaceClaimRequestRepository placeClaimRequestRepository,
                                        ModeratorRolePolicy moderatorRolePolicy) {
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<PlaceClaimView> execute(UUID actorUserId, int page, int size, PlaceClaimStatus status) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        PlaceClaimPagination.validate(page, size);
        moderatorRolePolicy.requireCurrentModerator(actorUserId);
        return placeClaimRequestRepository.findViews(null, status, page, size, true);
    }
}
