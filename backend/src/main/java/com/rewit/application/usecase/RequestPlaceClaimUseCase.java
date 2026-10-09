package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceClaimRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de reivindicação de um local por uma conta comercial (C9).
 *
 * <p>Regras: só o administrador da conta solicita (outra conta responde como inexistente); conta {@code REJECTED} não
 * solicita; o local precisa estar disponível ({@code ACTIVE}, como nas leituras públicas) e sem conta vinculada; no
 * máximo uma solicitação pendente por local, de qualquer conta.
 *
 * <p>Concorrência: o local é travado (FOR UPDATE) antes das checagens, o que serializa as solicitações do mesmo local
 * entre si e com a aprovação, que trava o mesmo local primeiro. O índice parcial único uq_place_claim_pending_place é
 * a garantia final: uma violação responde o mesmo 409 da checagem.
 */
@Service
public class RequestPlaceClaimUseCase {

    private static final String PENDING_UNIQUE_INDEX = "uq_place_claim_pending_place";

    private final BusinessAccountRepository businessAccountRepository;
    private final PlaceRepository placeRepository;
    private final PlaceClaimRequestRepository placeClaimRequestRepository;
    private final AccountStatusPolicy accountStatusPolicy;

    public RequestPlaceClaimUseCase(BusinessAccountRepository businessAccountRepository,
                                    PlaceRepository placeRepository,
                                    PlaceClaimRequestRepository placeClaimRequestRepository,
                                    AccountStatusPolicy accountStatusPolicy) {
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
    }

    @Transactional
    public PlaceClaimView execute(UUID actorUserId, UUID businessAccountId, UUID placeId, String evidenceDescription) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_ACCOUNT_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        // Valida a evidência antes de qualquer I/O
        PlaceClaimRequest claim = new PlaceClaimRequest(businessAccountId, placeId, evidenceDescription, Instant.now());

        accountStatusPolicy.requireOperational(actorUserId);

        BusinessAccount account = businessAccountRepository.findById(businessAccountId)
                .filter(candidate -> candidate.isAdministeredBy(actorUserId))
                .orElseThrow(RequestPlaceClaimUseCase::businessAccountNotFound);
        if (account.isRejected()) {
            throw new BusinessException("Conta comercial rejeitada não pode solicitar reivindicações", HttpStatus.CONFLICT,
                    "BUSINESS_ACCOUNT_REJECTED");
        }

        Place place = placeRepository.findByIdForUpdate(placeId)
                .filter(candidate -> CatalogService.PUBLIC_CATALOG_STATUS.equals(candidate.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
        if (place.getClaimedByBusinessId() != null) {
            throw placeAlreadyClaimed();
        }
        if (placeClaimRequestRepository.existsPendingByPlaceId(placeId)) {
            throw claimAlreadyPending();
        }

        try {
            return PlaceClaimView.of(placeClaimRequestRepository.save(claim), account, place);
        } catch (DataIntegrityViolationException ex) {
            if (CreateBusinessAccountUseCase.violates(ex, PENDING_UNIQUE_INDEX)) {
                throw claimAlreadyPending();
            }
            throw ex;
        }
    }

    /** Conta de outro usuário é indistinguível de conta inexistente. */
    static BusinessException businessAccountNotFound() {
        return new BusinessException("Conta comercial não encontrada", HttpStatus.NOT_FOUND, "BUSINESS_ACCOUNT_NOT_FOUND");
    }

    static BusinessException placeAlreadyClaimed() {
        return new BusinessException("O local já está vinculado a uma conta comercial", HttpStatus.CONFLICT,
                "PLACE_ALREADY_CLAIMED");
    }

    private static BusinessException claimAlreadyPending() {
        return new BusinessException("Já existe uma solicitação de reivindicação pendente para este local", HttpStatus.CONFLICT,
                "PLACE_CLAIM_ALREADY_PENDING");
    }
}
