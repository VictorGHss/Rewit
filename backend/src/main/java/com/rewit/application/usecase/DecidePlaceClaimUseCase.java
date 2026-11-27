package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceClaimModerationAuditLogRepository;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceClaimModerationAuditLog;
import com.rewit.domain.model.PlaceClaimRequest;
import com.rewit.infrastructure.persistence.routing.ModerationTransactional;
import com.rewit.infrastructure.security.ModerationAuditMacService;
import com.rewit.infrastructure.security.ModerationAuditPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
    private final ModeratorRolePolicy moderatorRolePolicy;
    private final PlaceClaimModerationAuditLogRepository placeClaimModerationAuditLogRepository;
    private final ModerationAuditMacService moderationAuditMacService;

    @Autowired
    public DecidePlaceClaimUseCase(PlaceClaimRequestRepository placeClaimRequestRepository,
                                   PlaceRepository placeRepository,
                                   BusinessAccountRepository businessAccountRepository,
                                   ModeratorRolePolicy moderatorRolePolicy,
                                   PlaceClaimModerationAuditLogRepository placeClaimModerationAuditLogRepository,
                                   ModerationAuditMacService moderationAuditMacService) {
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
        this.placeClaimModerationAuditLogRepository = Objects.requireNonNull(placeClaimModerationAuditLogRepository,
                "PlaceClaimModerationAuditLogRepository must not be null");
        this.moderationAuditMacService = Objects.requireNonNull(moderationAuditMacService,
                "ModerationAuditMacService must not be null");
    }

    public DecidePlaceClaimUseCase(PlaceClaimRequestRepository placeClaimRequestRepository,
                                   PlaceRepository placeRepository,
                                   BusinessAccountRepository businessAccountRepository,
                                   ModeratorRolePolicy moderatorRolePolicy) {
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
        this.placeClaimModerationAuditLogRepository = null;
        this.moderationAuditMacService = null;
    }

    @ModerationTransactional
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
        Instant decidedAt = ModerationAuditPayload.canonicalTimestamp(now);

        // Estado e role atuais: o JWT pode ser anterior a uma desativação ou mudança de role
        moderatorRolePolicy.requireCurrentModerator(actorUserId);

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

        PlaceClaimStatus previousStatus = claim.getStatus();
        PlaceClaimStatus targetStatus = decision == PlaceClaimDecision.APPROVE
                ? PlaceClaimStatus.APPROVED
                : PlaceClaimStatus.REJECTED;
        String decisionMac = moderationAuditMacService != null
                ? moderationAuditMacService.computeMac(ModerationAuditPayload.forPlaceClaimDecision(
                        claim.getId(),
                        place.getId(),
                        account.getId(),
                        actorUserId,
                        targetStatus.name(),
                        reason,
                        decidedAt))
                : null;

        if (decision == PlaceClaimDecision.APPROVE) {
            if (account.isRejected()) {
                throw new BusinessException("Conta comercial rejeitada não pode receber locais", HttpStatus.CONFLICT,
                        "BUSINESS_ACCOUNT_REJECTED");
            }
            if (place.getClaimedByBusinessId() != null
                    || !placeRepository.assignClaimedBusiness(place.getId(), account.getId())) {
                throw RequestPlaceClaimUseCase.placeAlreadyClaimed();
            }
            claim.approve(actorUserId, reason, decidedAt, decisionMac);
            if (account.approveVerification(decidedAt)) {
                account = businessAccountRepository.save(account);
            }
        } else {
            claim.reject(actorUserId, reason, decidedAt, decisionMac);
        }

        PlaceClaimRequest savedClaim = placeClaimRequestRepository.save(claim);

        if (placeClaimModerationAuditLogRepository != null) {
            PlaceClaimModerationAuditLog.Action auditAction = decision == PlaceClaimDecision.APPROVE
                    ? PlaceClaimModerationAuditLog.Action.APPROVE_CLAIM
                    : PlaceClaimModerationAuditLog.Action.REJECT_CLAIM;
            UUID auditId = UUID.randomUUID();
            String auditMac = moderationAuditMacService != null
                    ? moderationAuditMacService.computeMac(ModerationAuditPayload.forPlaceClaimAudit(
                            auditId,
                            savedClaim.getId(),
                            place.getId(),
                            account.getId(),
                            actorUserId,
                            auditAction.name(),
                            previousStatus.name(),
                            savedClaim.getStatus().name(),
                            reason,
                            decidedAt))
                    : null;
            placeClaimModerationAuditLogRepository.save(new PlaceClaimModerationAuditLog(
                    auditId,
                    savedClaim.getId(),
                    place.getId(),
                    account.getId(),
                    actorUserId,
                    auditAction,
                    previousStatus,
                    savedClaim.getStatus(),
                    reason,
                    decidedAt,
                    auditMac));
        }

        return PlaceClaimView.of(savedClaim, account, place);
    }

    private static BusinessException claimNotFound() {
        return new BusinessException("Solicitação de reivindicação não encontrada", HttpStatus.NOT_FOUND,
                "PLACE_CLAIM_NOT_FOUND");
    }
}
