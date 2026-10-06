package com.rewit.application.service;

import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Serviço de aplicação para gestão de Denúncias e Moderação Preventiva de Reviews (Step 19.0).
 */
@Service
public class ReportService {

    /**
     * Limiar determinístico de denúncias pendentes de usuários distintos para quarentena preventiva.
     */
    public static final int REPORT_THRESHOLD_FOR_UNDER_REVIEW = 3;

    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;
    private final AccountStatusPolicy accountStatusPolicy;
    private final UserFollowRepository userFollowRepository;
    private final RateLimiter rateLimiter;
    private final ReputationService reputationService;

    @org.springframework.beans.factory.annotation.Autowired
    public ReportService(
            ReportRepository reportRepository,
            ReviewRepository reviewRepository,
            AccountStatusPolicy accountStatusPolicy,
            UserFollowRepository userFollowRepository,
            RateLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ReputationService reputationService
    ) {
        this.reportRepository = Objects.requireNonNull(reportRepository, "ReportRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.userFollowRepository = Objects.requireNonNull(userFollowRepository, "UserFollowRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
        this.reputationService = reputationService;
    }

    public ReportService(
            ReportRepository reportRepository,
            ReviewRepository reviewRepository,
            AccountStatusPolicy accountStatusPolicy,
            UserFollowRepository userFollowRepository,
            RateLimiter rateLimiter
    ) {
        this(reportRepository, reviewRepository, accountStatusPolicy, userFollowRepository, rateLimiter, null);
    }

    public record CreateReportResult(Report report, boolean newlyCreated) {}

    /**
     * Registra uma denúncia para uma avaliação com garantias de atomicidade, visibilidade e idempotência.
     */
    @Transactional
    public CreateReportResult createReport(CreateReportCommand cmd) {
        Objects.requireNonNull(cmd, "CreateReportCommand cannot be null");
        if (cmd.reporterUserId() == null) {
            throw new BusinessException("O denunciante é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (cmd.reviewId() == null) {
            throw new BusinessException("A avaliação denunciada é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }

        accountStatusPolicy.requireOperational(cmd.reporterUserId());

        // 1. Rate limiting defensivo contra abuso
        rateLimiter.acquireOrThrow(RateLimitedAction.REPORT_CREATION, RateLimitSubject.ofUser(cmd.reporterUserId()));

        // 2. Trava a linha da Review para garantir atomicidade sob concorrência
        Review review = reviewRepository.findByIdForUpdate(cmd.reviewId())
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // 3. Validação de status: Reviews inativas (UNDER_REVIEW, REMOVED) não aceitam novas denúncias
        if (review.getStatus() != ReviewStatus.ACTIVE) {
            throw new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        }

        // 4. Bloqueio de Self-report
        if (review.getUserId().equals(cmd.reporterUserId())) {
            throw new BusinessException("O autor não pode denunciar a própria avaliação", HttpStatus.BAD_REQUEST, "SELF_REPORT_FORBIDDEN");
        }

        // 5. Verificação de visibilidade e autorização de leitura
        validateVisibility(review, cmd.reporterUserId());

        // 6. Idempotência: Se já houver denúncia deste usuário para esta avaliação, retorna a existente
        Optional<Report> existingOpt = reportRepository.findByReviewIdAndReporterUserId(cmd.reviewId(), cmd.reporterUserId());
        if (existingOpt.isPresent()) {
            return new CreateReportResult(existingOpt.get(), false);
        }

        // 7. Salva a nova denúncia
        Report savedReport;
        try {
            Report newReport = new Report(
                    null,
                    cmd.reviewId(),
                    cmd.reporterUserId(),
                    cmd.reason(),
                    cmd.detail()
            );
            savedReport = reportRepository.save(newReport);
        } catch (DataIntegrityViolationException ex) {
            // Em caso de colisão concorrente de unicidade, recupera o report salvo pela outra transação
            return reportRepository.findByReviewIdAndReporterUserId(cmd.reviewId(), cmd.reporterUserId())
                    .map(r -> new CreateReportResult(r, false))
                    .orElseThrow(() -> ex);
        }

        // 8. Moderação preventiva: contagem atômica de denúncias pendentes distintas
        long pendingCount = reportRepository.countPendingByReviewId(cmd.reviewId());
        if (pendingCount >= REPORT_THRESHOLD_FOR_UNDER_REVIEW && review.getStatus() == ReviewStatus.ACTIVE) {
            review.markUnderReview();
            reviewRepository.save(review);
            if (reputationService != null) {
                reputationService.recalculateAndSave(review.getUserId());
            }
        }

        return new CreateReportResult(savedReport, true);
    }

    private void validateVisibility(Review review, java.util.UUID requesterUserId) {
        String visibility = review.getVisibility() != null ? review.getVisibility() : "PUBLIC";
        switch (visibility) {
            case "PUBLIC" -> {
                // Público: qualquer usuário autenticado pode visualizar e denunciar
            }
            case "FOLLOWERS" -> {
                // Apenas seguidores confirmados podem visualizar e denunciar
                boolean isFollowing = userFollowRepository.isFollowing(requesterUserId, review.getUserId());
                if (!isFollowing) {
                    throw new BusinessException("Acesso negado para denunciar esta avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
                }
            }
            case "PRIVATE" -> {
                // Avaliação privada não pode ser visualizada nem denunciada por terceiros
                throw new BusinessException("Acesso negado para denunciar esta avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
            default -> throw new BusinessException("Acesso negado para denunciar esta avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }
}
