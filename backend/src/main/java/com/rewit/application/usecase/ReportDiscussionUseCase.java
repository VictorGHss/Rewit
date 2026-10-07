package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionResult;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Denúncia de discussão com auto-quarentena (C3 D1): a terceira denúncia PENDING de usuários distintos leva a
 * discussão de ACTIVE para UNDER_REVIEW na mesma transação.
 *
 * <p><b>Concorrência</b>: a linha da discussão é travada ({@code FOR UPDATE}) antes de inserir e contar. Denúncias
 * concorrentes da mesma discussão são serializadas, e cada contagem enxerga as denúncias já confirmadas pelas
 * anteriores: a quarentena acontece exatamente na terceira, nunca é perdida e nunca ocorre duas vezes.
 *
 * <p><b>Privacidade</b>: quem denuncia recebe a mesma confirmação na primeira denúncia, na repetida e na que
 * dispara a quarentena. Discussão indisponível (removida, em quarentena ou numa conversa em quarentena) responde
 * 404, sem distinguir o motivo. A quarentena não altera reputação, estatísticas nem gera notificação.
 */
@Service
public class ReportDiscussionUseCase {

    public static final int QUARANTINE_THRESHOLD = 3;

    private final DiscussionRepository discussionRepository;
    private final DiscussionReportRepository discussionReportRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final AccountStatusPolicy accountStatusPolicy;
    private final RateLimiter rateLimiter;

    public ReportDiscussionUseCase(DiscussionRepository discussionRepository,
                                   DiscussionReportRepository discussionReportRepository,
                                   ReviewRepository reviewRepository,
                                   ReviewVisibilityPolicy reviewVisibilityPolicy,
                                   AccountStatusPolicy accountStatusPolicy,
                                   RateLimiter rateLimiter) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.discussionReportRepository = Objects.requireNonNull(discussionReportRepository, "DiscussionReportRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy, "ReviewVisibilityPolicy must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    @Transactional
    public ReportDiscussionResult execute(ReportDiscussionCommand cmd) {
        Objects.requireNonNull(cmd, "ReportDiscussionCommand cannot be null");
        if (cmd.reporterUserId() == null) {
            throw new BusinessException("O denunciante é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (cmd.discussionId() == null) {
            throw new BusinessException("A discussão denunciada é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        if (cmd.reason() == null) {
            throw new BusinessException("O motivo da denúncia é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_REPORT_REASON");
        }

        // 1. Conta apta a operar e limite de denúncias (o mesmo das denúncias de avaliação)
        accountStatusPolicy.requireOperational(cmd.reporterUserId());
        rateLimiter.acquireOrThrow(RateLimitedAction.REPORT_CREATION, RateLimitSubject.ofUser(cmd.reporterUserId()));

        // 2. Lock da discussão antes de inserir e contar: serializa denúncias concorrentes da mesma discussão
        ReviewDiscussion discussion = discussionRepository.findByIdForUpdate(cmd.discussionId())
                .orElseThrow(ReportDiscussionUseCase::discussionNotFound);

        // 3. A avaliação precisa estar acessível ao denunciante (ACTIVE e visibilidade)
        Review review = reviewRepository.findById(discussion.getReviewId())
                .orElseThrow(ReportDiscussionUseCase::discussionNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, cmd.reporterUserId());

        // 4. Autor não denuncia o próprio comentário
        if (discussion.getUserId().equals(cmd.reporterUserId())) {
            throw new BusinessException("O autor não pode denunciar o próprio comentário", HttpStatus.BAD_REQUEST, "SELF_REPORT_FORBIDDEN");
        }

        // 5. Denúncia repetida: mesma confirmação, sem nova contagem (antes da checagem de estado, para não revelar
        //    a quarentena a quem já denunciou)
        Optional<DiscussionReport> existing =
                discussionReportRepository.findByDiscussionIdAndReporterUserId(discussion.getId(), cmd.reporterUserId());
        if (existing.isPresent()) {
            return new ReportDiscussionResult(existing.get().getId(), false);
        }

        // 6. Só discussões publicamente visíveis podem ser denunciadas
        if (!isPubliclyVisible(discussion)) {
            throw discussionNotFound();
        }

        // 7. Persistência e contagem sob o lock
        DiscussionReport saved = discussionReportRepository.save(new DiscussionReport(
                null, discussion.getId(), cmd.reporterUserId(), cmd.reason(), cmd.detail()));

        if (discussion.isActive()
                && discussionReportRepository.countPendingByDiscussionId(discussion.getId()) >= QUARANTINE_THRESHOLD) {
            discussion.markUnderReview(Instant.now());
            discussionRepository.save(discussion);
        }

        return new ReportDiscussionResult(saved.getId(), true);
    }

    /**
     * ACTIVE e, se for resposta, fora de uma conversa em quarentena. Resposta a um comentário removido continua
     * visível (C3 D2) e pode ser denunciada.
     */
    private boolean isPubliclyVisible(ReviewDiscussion discussion) {
        if (!discussion.isActive()) {
            return false;
        }
        if (discussion.getParentId() == null) {
            return true;
        }
        return discussionRepository.findById(discussion.getParentId())
                .map(parent -> parent.getStatus() != DiscussionStatus.UNDER_REVIEW)
                .orElse(false);
    }

    private static BusinessException discussionNotFound() {
        return new BusinessException("Comentário não encontrado ou indisponível", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND");
    }
}
