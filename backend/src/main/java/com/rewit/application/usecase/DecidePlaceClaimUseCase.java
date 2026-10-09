package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceClaimRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Decisão de uma solicitação de reivindicação por moderador ou administrador (C9), com justificativa obrigatória.
 *
 * <p>Aprovar vincula o local à conta e, na primeira aprovação, verifica a conta ({@code APPROVED}); tudo na mesma
 * transação. Rejeitar decide só a solicitação: a conta continua apta a reivindicar outros locais. Uma solicitação
 * decidida não volta a ser decidida.
 *
 * <p>Concorrência: trava o local, depois a solicitação e por fim a conta, na mesma ordem em que a solicitação trava o
 * local primeiro. Duas aprovações para o mesmo local se serializam no local; a vinculação é um UPDATE condicional
 * ({@code claimed_by_business_id IS NULL}), então o local nunca fica com duas contas.
 *
 * <p>A role é conferida aqui também, além da rota HTTP: quem não é MODERATOR/ADMIN recebe 403, e o administrador da
 * conta solicitante não decide a própria solicitação.
 */
@Service
public class DecidePlaceClaimUseCase {

    private final PlaceClaimRequestRepository placeClaimRequestRepository;
    private final PlaceRepository placeRepository;
    private final BusinessAccountRepository businessAccountRepository;
    private final UserRepository userRepository;
    private final AccountStatusPolicy accountStatusPolicy;

    public DecidePlaceClaimUseCase(PlaceClaimRequestRepository placeClaimRequestRepository,
                                   PlaceRepository placeRepository,
                                   BusinessAccountRepository businessAccountRepository,
                                   UserRepository userRepository,
                                   AccountStatusPolicy accountStatusPolicy) {
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
    }

    @Transactional
    public PlaceClaimView execute(UUID actorUserId, UUID claimId, PlaceClaimDecision decision, String justification,
                                  Instant now) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (claimId == null) {
            throw new BusinessException("A solicitação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_PLACE_CLAIM_ID");
        }
        if (decision == null) {
            throw new BusinessException("A decisão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_DECISION");
        }
        String reason = PlaceClaimRequest.normalizeDecisionReason(justification);
        Instant decidedAt = now != null ? now : Instant.now();

        // Estado e role atuais: o JWT pode ser anterior a uma desativação ou mudança de role
        accountStatusPolicy.requireOperational(actorUserId);
        boolean moderator = userRepository.findById(actorUserId)
                .map(user -> user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN)
                .orElse(false);
        if (!moderator) {
            throw new BusinessException("Apenas moderadores e administradores decidem reivindicações", HttpStatus.FORBIDDEN,
                    "FORBIDDEN");
        }

        PlaceClaimRequest unlocked = placeClaimRequestRepository.findById(claimId)
                .orElseThrow(DecidePlaceClaimUseCase::claimNotFound);

        // Ordem de lock: local -> solicitação -> conta
        Place place = placeRepository.findByIdForUpdate(unlocked.getPlaceId())
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
        PlaceClaimRequest claim = placeClaimRequestRepository.findByIdForUpdate(claimId)
                .orElseThrow(DecidePlaceClaimUseCase::claimNotFound);
        BusinessAccount account = businessAccountRepository.findByIdForUpdate(claim.getBusinessAccountId())
                .orElseThrow(RequestPlaceClaimUseCase::businessAccountNotFound);

        if (account.isAdministeredBy(actorUserId)) {
            throw new BusinessException("O administrador da conta não decide a própria solicitação", HttpStatus.FORBIDDEN,
                    "SELF_DECISION_FORBIDDEN");
        }
        if (!claim.isPending()) {
            throw new BusinessException("A solicitação de reivindicação já foi decidida", HttpStatus.CONFLICT,
                    "PLACE_CLAIM_ALREADY_DECIDED");
        }

        if (decision == PlaceClaimDecision.APPROVE) {
            if (account.isRejected()) {
                throw new BusinessException("Conta comercial rejeitada não pode receber locais", HttpStatus.CONFLICT,
                        "BUSINESS_ACCOUNT_REJECTED");
            }
            if (place.getClaimedByBusinessId() != null
                    || !placeRepository.assignClaimedBusiness(place.getId(), account.getId())) {
                throw RequestPlaceClaimUseCase.placeAlreadyClaimed();
            }
            claim.approve(actorUserId, reason, decidedAt);
            if (account.approveVerification(decidedAt)) {
                account = businessAccountRepository.save(account);
            }
        } else {
            claim.reject(actorUserId, reason, decidedAt);
        }

        return PlaceClaimView.of(placeClaimRequestRepository.save(claim), account, place);
    }

    private static BusinessException claimNotFound() {
        return new BusinessException("Solicitação de reivindicação não encontrada", HttpStatus.NOT_FOUND,
                "PLACE_CLAIM_NOT_FOUND");
    }
}
