package com.rewit.application.usecase;

import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewResult;
import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso para moderação administrativa de avaliações (Step 26.2).
 *
 * <p>Invariantes e regras aplicadas:
 * 1. Execução transacional e atômica (@Transactional).
 * 2. Carregamento com lock pessimista de escrita (SELECT ... FOR UPDATE) via findByIdForUpdate.
 * 3. Bloqueio de auto-moderação: o moderador não pode moderar sua própria avaliação (SELF_MODERATION_FORBIDDEN).
 * 4. Bloqueio de conflito de interesses: o moderador não pode moderar uma avaliação na qual possui denúncia PENDING (REPORTER_CANNOT_MODERATE).
 * 5. Validação rigorosa de justificativa obrigatória (entre 15 e 1000 caracteres) e reasonCode.
 * 6. Validação de transições de status válidas (ACTIVE -> REMOVED, UNDER_REVIEW -> REMOVED, UNDER_REVIEW -> ACTIVE).
 * 7. Resolução em lote de todas as denúncias pendentes vinculadas (ACCEPTED para REMOVE_REVIEW; REJECTED para RESTORE_REVIEW).
 * 8. Recomputação integral de RateableTargetStats no PostgreSQL em ordem determinística (targetId ASC) para prevenção de deadlocks.
 * 9. Recálculo factual da reputação do autor (UserReputation).
 * 10. Marcação lógica de mídias ativas como REMOVED em caso de remoção (sem deleção física de storage).
 * 11. Preservação histórica integral de dados de check-in (sem exclusão).
 * 12. Geração imutável de registro de auditoria append-only em ModerationAuditLog.
 */
@Service
public class ModerateReviewUseCase {

    private final ReviewRepository reviewRepository;
    private final ReportRepository reportRepository;
    private final ModerationAuditLogRepository moderationAuditLogRepository;
    private final RateableTargetStatsRepository rateableTargetStatsRepository;
    private final ReputationService reputationService;
    private final ReviewMediaRepository reviewMediaRepository;

    @Autowired
    public ModerateReviewUseCase(
            ReviewRepository reviewRepository,
            ReportRepository reportRepository,
            ModerationAuditLogRepository moderationAuditLogRepository,
            RateableTargetStatsRepository rateableTargetStatsRepository,
            ReputationService reputationService,
            @Autowired(required = false) ReviewMediaRepository reviewMediaRepository
    ) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository must not be null");
        this.moderationAuditLogRepository = Objects.requireNonNull(moderationAuditLogRepository, "moderationAuditLogRepository must not be null");
        this.rateableTargetStatsRepository = Objects.requireNonNull(rateableTargetStatsRepository, "rateableTargetStatsRepository must not be null");
        this.reputationService = Objects.requireNonNull(reputationService, "reputationService must not be null");
        this.reviewMediaRepository = reviewMediaRepository;
    }

    public ModerateReviewUseCase(
            ReviewRepository reviewRepository,
            ReportRepository reportRepository,
            ModerationAuditLogRepository moderationAuditLogRepository,
            RateableTargetStatsRepository rateableTargetStatsRepository,
            ReputationService reputationService
    ) {
        this(reviewRepository, reportRepository, moderationAuditLogRepository, rateableTargetStatsRepository, reputationService, null);
    }

    @Transactional
    public ModerateReviewResult execute(
            UUID reviewId,
            UUID moderatorUserId,
            ModerationAction action,
            String reasonCode,
            String justification,
            Instant now
    ) {
        return execute(new ModerateReviewCommand(reviewId, moderatorUserId, action, reasonCode, justification, now));
    }

    @Transactional
    public ModerateReviewResult execute(ModerateReviewCommand command) {
        if (command == null) {
            throw new BusinessException("Comando de moderação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_COMMAND");
        }
        if (command.reviewId() == null) {
            throw new BusinessException("Identificador da avaliação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
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
        if (command.justification() == null) {
            throw new BusinessException("A justificativa da decisão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_JUSTIFICATION");
        }
        if (command.justification().isBlank()) {
            throw new BusinessException("A justificativa da decisão não pode ser vazia", HttpStatus.BAD_REQUEST, "BLANK_JUSTIFICATION");
        }

        String trimmedJustification = command.justification().trim();
        if (trimmedJustification.length() < 15) {
            throw new BusinessException("A justificativa deve conter no mínimo 15 caracteres", HttpStatus.BAD_REQUEST, "INVALID_JUSTIFICATION_LENGTH");
        }
        if (trimmedJustification.length() > 1000) {
            throw new BusinessException("A justificativa não pode exceder 1000 caracteres", HttpStatus.BAD_REQUEST, "INVALID_JUSTIFICATION_LENGTH");
        }

        Instant now = command.now() != null ? command.now() : Instant.now();

        // 1. Carregamento transacional da Review com Lock Pessimista de Escrita
        Review review = reviewRepository.findByIdForUpdate(command.reviewId())
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // 2. Validação de conflito: moderador não pode ser o autor da própria avaliação
        if (review.getUserId().equals(command.moderatorUserId())) {
            throw new BusinessException("O moderador não pode moderar a própria avaliação", HttpStatus.FORBIDDEN, "SELF_MODERATION_FORBIDDEN");
        }

        // 3. Carregamento dos Reports PENDING vinculados à Review
        List<Report> pendingReports = reportRepository.findPendingByReviewId(review.getId());

        // 4. Validação de conflito: moderador não pode ser denunciante com denúncia pendente para esta avaliação
        boolean isPendingReporter = pendingReports.stream()
                .anyMatch(r -> r.getReporterUserId().equals(command.moderatorUserId()));
        if (isPendingReporter) {
            throw new BusinessException("O moderador possui denúncia pendente para esta avaliação", HttpStatus.FORBIDDEN, "REPORTER_CANNOT_MODERATE");
        }

        // 5. Validação de transição de estado da Review
        ReviewStatus previousStatus = review.getStatus();
        if (command.action() == ModerationAction.REMOVE_REVIEW) {
            if (previousStatus == ReviewStatus.REMOVED) {
                throw new BusinessException("A avaliação já se encontra removida", HttpStatus.CONFLICT, "REVIEW_ALREADY_REMOVED");
            }
            if (previousStatus != ReviewStatus.ACTIVE && previousStatus != ReviewStatus.UNDER_REVIEW) {
                throw new BusinessException("Transição de estado inválida para remoção", HttpStatus.CONFLICT, "INVALID_REVIEW_STATUS_TRANSITION");
            }
            review.markRemovedByModerator(now);
        } else if (command.action() == ModerationAction.RESTORE_REVIEW) {
            if (previousStatus == ReviewStatus.ACTIVE) {
                throw new BusinessException("A avaliação já se encontra ativa", HttpStatus.CONFLICT, "REVIEW_ALREADY_ACTIVE");
            }
            if (previousStatus != ReviewStatus.UNDER_REVIEW) {
                throw new BusinessException("Transição de estado inválida para restauração", HttpStatus.CONFLICT, "INVALID_REVIEW_STATUS_TRANSITION");
            }
            review.restoreFromUnderReview(now);
        }

        // 6. Resolução em lote de todos os Reports PENDING vinculados à Review
        if (command.action() == ModerationAction.REMOVE_REVIEW) {
            for (Report report : pendingReports) {
                report.resolveAsAccepted(now);
            }
        } else {
            for (Report report : pendingReports) {
                report.resolveAsRejected(now);
            }
        }

        // 7. Persistência da Review e dos Reports resolvidos
        reviewRepository.save(review);
        reportRepository.saveAll(pendingReports);

        // 8. Recomputação integral de RateableTargetStats em ordem determinística (targetId ASC)
        review.getTargets().stream()
                .map(target -> target.getTargetId())
                .distinct()
                .sorted()
                .forEach(targetId -> rateableTargetStatsRepository.recalculateAndSave(targetId));

        // 9. Recálculo factual da reputação do autor da avaliação
        reputationService.recalculateAndSave(review.getUserId());

        // 10. Processamento de mídia lógica em caso de remoção
        if (command.action() == ModerationAction.REMOVE_REVIEW && reviewMediaRepository != null) {
            List<ReviewMedia> activeMedia = reviewMediaRepository.findActiveByReviewId(review.getId());
            for (ReviewMedia media : activeMedia) {
                media.markRemoved();
                reviewMediaRepository.save(media);
            }
        }

        // 11. Criação e persistência do registro imutável de auditoria (append-only)
        ModerationDecision decision = (command.action() == ModerationAction.REMOVE_REVIEW)
                ? ModerationDecision.ACCEPTED
                : ModerationDecision.REJECTED;

        ModerationAuditLog auditLog = new ModerationAuditLog(
                UUID.randomUUID(),
                review.getId(),
                command.moderatorUserId(),
                command.action(),
                decision,
                command.reasonCode().trim(),
                trimmedJustification,
                previousStatus,
                review.getStatus(),
                pendingReports.size(),
                now
        );
        moderationAuditLogRepository.save(auditLog);

        return new ModerateReviewResult(auditLog, review);
    }
}
