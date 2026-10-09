package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Histórico de reivindicações de uma conta comercial, visível só ao administrador dela (C9). Mais recentes primeiro;
 * filtro opcional por status. Conta de outro usuário responde como inexistente.
 */
@Service
public class ListBusinessPlaceClaimsUseCase {

    private final BusinessAccountRepository businessAccountRepository;
    private final PlaceClaimRequestRepository placeClaimRequestRepository;

    public ListBusinessPlaceClaimsUseCase(BusinessAccountRepository businessAccountRepository,
                                          PlaceClaimRequestRepository placeClaimRequestRepository) {
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<PlaceClaimView> execute(UUID actorUserId, UUID businessAccountId, PlaceClaimStatus status,
                                              int page, int size) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_ACCOUNT_ID");
        }
        PlaceClaimPagination.validate(page, size);

        businessAccountRepository.findById(businessAccountId)
                .filter(account -> account.isAdministeredBy(actorUserId))
                .orElseThrow(RequestPlaceClaimUseCase::businessAccountNotFound);

        return placeClaimRequestRepository.findViews(businessAccountId, status, page, size, false);
    }
}
