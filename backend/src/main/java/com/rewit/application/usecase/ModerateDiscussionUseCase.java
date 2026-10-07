package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionResult;
import com.rewit.application.port.DiscussionModerationAuditLogRepository;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Moderação administrativa de uma discussão em análise (C3): {@code UNDER_REVIEW -> REMOVED} (denúncias
 * procedentes) ou {@code UNDER_REVIEW -> ACTIVE} (denúncias improcedentes), numa única transação com lock
 * pessimista da discussão, resolução das denúncias pendentes e registro append-only de auditoria.
 *
 * <p>O lock é o mesmo usado pela denúncia e pela exclusão pelo autor: as três operações sobre a mesma discussão
 * são serializadas. Não altera reputação nem estatísticas e não gera notificação.
 */
@Service
public class ModerateDiscussionUseCase {

    public static final int MAX_REASON_CODE_LENGTH = 64;
    public static final int MIN_JUSTIFICATION_LENGTH = 15;
    public static final int MAX_JUSTIFICATION_LENGTH = 1000;

    private final DiscussionRepository discussionRepository;
    private final DiscussionReportRepository discussionReportRepository;
    private final DiscussionModerationAuditLogRepository auditLogRepository;
    private final AccountStatusPolicy accountStatusPolicy;

    public ModerateDiscussionUseCase(DiscussionRepository discussionRepository,
                                     DiscussionReportRepository discussionReportRepository,
                                     DiscussionModerationAuditLogRepository auditLogRepository,
                                     AccountStatusPolicy accountStatusPolicy) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.discussionReportRepository = Objects.requireNonNull(discussionReportRepository, "DiscussionReportRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "DiscussionModerationAuditLogRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
    }

    @Transactional
    public ModerateDiscussionResult execute(ModerateDiscussionCommand command) {
        validate(command);
        String reasonCode = command.reasonCode().trim();
        String justification = command.justification().trim();
        Instant now = command.now() != null ? command.now() : Instant.now();

        // Estado atual da conta do moderador antes do lock: o JWT, e a role nele, pode ser anterior à desativação
        accountStatusPolicy.requireOperational(command.moderatorUserId());

        // 1. Lock pessimista da discussão
        ReviewDiscussion discussion = discussionRepository.findByIdForUpdate(command.discussionId())
                .orElseThrow(() -> new BusinessException("Comentário não encontrado", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND"));

        // 2. Conflitos de interesse: autor do comentário ou quem o denunciou
        if (discussion.getUserId().equals(command.moderatorUserId())) {
            throw new BusinessException("O moderador não pode moderar o próprio comentário", HttpStatus.FORBIDDEN, "SELF_MODERATION_FORBIDDEN");
        }
        if (discussionReportRepository.existsByDiscussionIdAndReporterUserId(discussion.getId(), command.moderatorUserId())) {
            throw new BusinessException("O moderador denunciou este comentário", HttpStatus.FORBIDDEN, "REPORTER_CANNOT_MODERATE");
        }

        // 3. Somente discussões em análise são moderadas
        DiscussionStatus previousStatus = discussion.getStatus();
        if (previousStatus != DiscussionStatus.UNDER_REVIEW) {
            throw new BusinessException("O comentário não está em análise", HttpStatus.CONFLICT, "DISCUSSION_NOT_UNDER_REVIEW");
        }
        if (command.action() == DiscussionModerationAction.REMOVE_DISCUSSION) {
            discussion.markRemovedByModerator(now);
        } else {
            discussion.restoreFromUnderReview(now);
        }

        // 4. Resolução de todas as denúncias pendentes
        List<DiscussionReport> pendingReports = discussionReportRepository.findPendingByDiscussionId(discussion.getId());
        for (DiscussionReport report : pendingReports) {
            if (command.action() == DiscussionModerationAction.REMOVE_DISCUSSION) {
                report.resolveAsAccepted(now);
            } else {
                report.resolveAsRejected(now);
            }
        }

        ReviewDiscussion saved = discussionRepository.save(discussion);
        discussionReportRepository.saveAll(pendingReports);

        // 5. Auditoria append-only
        DiscussionModerationAuditLog auditLog = auditLogRepository.save(new DiscussionModerationAuditLog(
                UUID.randomUUID(),
                discussion.getId(),
                command.moderatorUserId(),
                command.action(),
                command.action().decision(),
                reasonCode,
                justification,
                previousStatus,
                saved.getStatus(),
                pendingReports.size(),
                now
        ));

        return new ModerateDiscussionResult(auditLog, saved);
    }

    private static void validate(ModerateDiscussionCommand command) {
        if (command == null) {
            throw new BusinessException("Comando de moderação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_COMMAND");
        }
        if (command.discussionId() == null) {
            throw new BusinessException("Identificador do comentário é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        if (command.moderatorUserId() == null) {
            throw new BusinessException("Identificador do moderador é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.action() == null) {
            throw new BusinessException("A ação de moderação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_MODERATION_ACTION");
        }
        if (command.reasonCode() == null || command.reasonCode().isBlank()) {
            throw new BusinessException("O código do motivo da decisão é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REASON_CODE");
        }
        if (command.reasonCode().trim().length() > MAX_REASON_CODE_LENGTH) {
            throw new BusinessException("O código do motivo não pode exceder 64 caracteres", HttpStatus.BAD_REQUEST, "INVALID_REASON_CODE_LENGTH");
        }
        if (command.justification() == null || command.justification().isBlank()) {
            throw new BusinessException("A justificativa da decisão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_JUSTIFICATION");
        }
        int length = command.justification().trim().length();
        if (length < MIN_JUSTIFICATION_LENGTH || length > MAX_JUSTIFICATION_LENGTH) {
            throw new BusinessException("A justificativa deve ter entre 15 e 1000 caracteres", HttpStatus.BAD_REQUEST, "INVALID_JUSTIFICATION_LENGTH");
        }
    }
}
