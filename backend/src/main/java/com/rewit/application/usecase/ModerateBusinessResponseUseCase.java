package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseModerationAuditLogRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Moderação administrativa de uma resposta oficial de empresa (C11): remoção ou restauração, com auditoria
 * append-only própria ({@code business_response_moderation_audit_logs}) na mesma transação.
 *
 * <ul>
 *   <li>Cargo e conta do moderador revalidados no banco antes de qualquer leitura do alvo
 *       ({@link ModeratorRolePolicy}: inoperante {@code 401 ACCOUNT_DISABLED}; sem MODERATOR/ADMIN
 *       {@code 403 FORBIDDEN}), como nas demais moderações (C10).</li>
 *   <li>Conflito de interesse: quem escreveu a resposta ou administra a conta autora não a modera
 *       ({@code 403 SELF_MODERATION_FORBIDDEN}).</li>
 *   <li>Transições do domínio: remover exige resposta ativa ({@code 409 BUSINESS_RESPONSE_ALREADY_REMOVED} se já
 *       removida, pela empresa ou pela moderação); restaurar só desfaz remoção da moderação
 *       ({@code 409 BUSINESS_RESPONSE_NOT_RESTORABLE}).</li>
 *   <li>Restaurar exige a representação elegível agora (decisão B: conta aprovada, administrador atual operacional,
 *       local ativo vinculado à conta), avaliada por {@link BusinessRepresentationPolicy#currentIneligibility} sem
 *       lock: travar o local depois da resposta inverteria a ordem da publicação, que trava o local antes de gravar a
 *       resposta. Inelegível responde {@code 409 BUSINESS_RESPONSE_NOT_RESTORABLE}; se a elegibilidade cair depois da
 *       checagem, a resposta restaurada fica oculta pela leitura pública até voltar.</li>
 *   <li>Restaurar quando a empresa já tem outra resposta ativa no mesmo alvo responde
 *       {@code 409 BUSINESS_RESPONSE_ALREADY_EXISTS}: pela checagem prévia ou, numa corrida, pela violação de
 *       {@code uq_business_responses_review_active}/{@code uq_business_responses_discussion_active}. A exceção sai do
 *       método transacional, então a transação inteira é desfeita: a resposta antiga fica removida, a nova
 *       inalterada e nenhuma auditoria de restauração é gravada.</li>
 * </ul>
 *
 * <p>A resposta é travada (FOR UPDATE) antes da transição; a auditoria só é gravada depois de a resposta ser salva.
 * Avaliação, comentário e denúncias não são alterados.
 */
@Service
public class ModerateBusinessResponseUseCase {

    private final BusinessResponseRepository businessResponseRepository;
    private final BusinessResponseModerationAuditLogRepository auditLogRepository;
    private final BusinessAccountRepository businessAccountRepository;
    private final ModeratorRolePolicy moderatorRolePolicy;
    private final BusinessRepresentationPolicy representationPolicy;

    public ModerateBusinessResponseUseCase(BusinessResponseRepository businessResponseRepository,
                                           BusinessResponseModerationAuditLogRepository auditLogRepository,
                                           BusinessAccountRepository businessAccountRepository,
                                           ModeratorRolePolicy moderatorRolePolicy,
                                           BusinessRepresentationPolicy representationPolicy) {
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository,
                "BusinessResponseModerationAuditLogRepository must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
    }

    @Transactional
    public BusinessResponseModerationResult execute(ModerateBusinessResponseCommand command) {
        validate(command);
        String reasonCode = command.reasonCode().trim();
        String justification = command.justification().trim();
        Instant now = command.now() != null ? command.now() : Instant.now();
        UUID moderatorUserId = command.moderatorUserId();

        // Estado e cargo atuais do moderador antes de ler o alvo: o JWT pode ser anterior a uma desativação ou a um
        // rebaixamento
        moderatorRolePolicy.requireCurrentModerator(moderatorUserId);

        BusinessResponse response = businessResponseRepository.findByIdForUpdate(command.responseId())
                .orElseThrow(() -> new BusinessException("Resposta oficial não encontrada", HttpStatus.NOT_FOUND,
                        "BUSINESS_RESPONSE_NOT_FOUND"));

        boolean administersAuthor = businessAccountRepository.findById(response.getBusinessAccountId())
                .map(account -> account.isAdministeredBy(moderatorUserId))
                .orElse(false);
        if (administersAuthor || response.getAuthorUserId().equals(moderatorUserId)) {
            throw new BusinessException("O moderador não pode moderar uma resposta da própria empresa",
                    HttpStatus.FORBIDDEN, "SELF_MODERATION_FORBIDDEN");
        }

        BusinessResponseStatus previousStatus = response.getStatus();
        if (command.action() == BusinessResponseModerationAction.REMOVE_RESPONSE) {
            response.removeByModeration(now);
        } else {
            response.restoreByModeration(now);
            Set<BusinessRepresentationIneligibility> ineligibility = representationPolicy.currentIneligibility(
                    response.getBusinessAccountId(), response.getPlaceId());
            if (!ineligibility.isEmpty()) {
                throw representationNotEligible(ineligibility);
            }
            // Checagem prévia da unicidade; o índice parcial continua sendo a garantia numa corrida
            boolean anotherActive = businessResponseRepository
                    .findActive(response.getReviewId(), response.getDiscussionId(), response.getBusinessAccountId())
                    .isPresent();
            if (anotherActive) {
                throw activeResponseExists();
            }
        }

        BusinessResponse saved;
        try {
            saved = businessResponseRepository.save(response);
        } catch (DataIntegrityViolationException ex) {
            if (CreateBusinessAccountUseCase.violates(ex, PublishReviewBusinessResponseUseCase.ACTIVE_REVIEW_RESPONSE_INDEX)
                    || CreateBusinessAccountUseCase.violates(ex,
                    PublishDiscussionBusinessResponseUseCase.ACTIVE_DISCUSSION_RESPONSE_INDEX)) {
                throw activeResponseExists();
            }
            throw ex;
        }

        BusinessResponseModerationAuditLog auditLog = auditLogRepository.save(new BusinessResponseModerationAuditLog(
                UUID.randomUUID(), saved.getId(), moderatorUserId, command.action(), reasonCode, justification,
                previousStatus, saved.getStatus(), now));

        return BusinessResponseModerationResult.of(auditLog, saved);
    }

    /**
     * O mesmo código das demais restaurações recusadas; o motivo vai no detalhe, sem o estado pessoal do
     * administrador.
     */
    private static BusinessException representationNotEligible(Set<BusinessRepresentationIneligibility> reasons) {
        String detail = reasons.stream()
                .map(reason -> switch (reason) {
                    case ACCOUNT_NOT_APPROVED -> "a conta comercial não está aprovada";
                    case ADMINISTRATOR_NOT_OPERATIONAL -> "o administrador da conta comercial não está operacional";
                    case PLACE_NOT_REPRESENTED -> "o local não está ativo ou não está vinculado à conta comercial";
                })
                .collect(Collectors.joining("; "));
        return new BusinessException("A representação comercial da resposta não está elegível (" + detail
                + "); a resposta removida não pode ser restaurada", HttpStatus.CONFLICT,
                "BUSINESS_RESPONSE_NOT_RESTORABLE");
    }

    private static BusinessException activeResponseExists() {
        return PublishReviewBusinessResponseUseCase.alreadyExists(
                "A empresa já tem outra resposta ativa neste alvo; a resposta removida não pode ser restaurada");
    }

    private static void validate(ModerateBusinessResponseCommand command) {
        if (command == null) {
            throw new BusinessException("Comando de moderação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_COMMAND");
        }
        if (command.moderatorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.responseId() == null) {
            throw new BusinessException("A resposta é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_RESPONSE_ID");
        }
        if (command.action() == null) {
            throw new BusinessException("A ação de moderação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_MODERATION_ACTION");
        }
        if (command.reasonCode() == null || command.reasonCode().isBlank()) {
            throw new BusinessException("O código do motivo da decisão é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REASON_CODE");
        }
        if (command.reasonCode().trim().length() > BusinessResponseModerationAuditLog.MAX_REASON_CODE_LENGTH) {
            throw new BusinessException("O código do motivo não pode exceder 64 caracteres", HttpStatus.BAD_REQUEST,
                    "INVALID_REASON_CODE_LENGTH");
        }
        if (command.justification() == null || command.justification().isBlank()) {
            throw new BusinessException("A justificativa da decisão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_JUSTIFICATION");
        }
        int length = command.justification().trim().length();
        if (length < ModerateDiscussionUseCase.MIN_JUSTIFICATION_LENGTH
                || length > ModerateDiscussionUseCase.MAX_JUSTIFICATION_LENGTH) {
            throw new BusinessException("A justificativa deve ter entre 15 e 1000 caracteres", HttpStatus.BAD_REQUEST,
                    "INVALID_JUSTIFICATION_LENGTH");
        }
    }
}
