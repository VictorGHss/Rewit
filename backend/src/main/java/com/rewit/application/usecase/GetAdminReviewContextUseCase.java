package com.rewit.application.usecase;

import com.rewit.application.dto.report.ReportDtos.AdminReviewAuditView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewContextView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewReportView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewTargetView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewView;
import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.RateableTargetRepository.TargetDisplay;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contexto administrativo de uma avaliação denunciada: conteúdo e status interno (inclusive se removida), alvos com
 * nome de exibição, todas as denúncias e o histórico de moderação. Somente leitura. Sem identidades: autor,
 * denunciantes e moderadores ficam fora da visão, mesmo em avaliação não anônima.
 *
 * <p>Consultas fixas, independentes do número de alvos e denúncias: avaliação (com alvos), denúncias, auditoria e
 * nomes dos alvos em lote.
 */
@Service
public class GetAdminReviewContextUseCase {

    private final ReviewRepository reviewRepository;
    private final ReportRepository reportRepository;
    private final ModerationAuditLogRepository auditLogRepository;
    private final RateableTargetRepository rateableTargetRepository;

    public GetAdminReviewContextUseCase(ReviewRepository reviewRepository,
                                        ReportRepository reportRepository,
                                        ModerationAuditLogRepository auditLogRepository,
                                        RateableTargetRepository rateableTargetRepository) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reportRepository = Objects.requireNonNull(reportRepository, "ReportRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "ModerationAuditLogRepository must not be null");
        this.rateableTargetRepository = Objects.requireNonNull(rateableTargetRepository,
                "RateableTargetRepository must not be null");
    }

    @Transactional(readOnly = true)
    public AdminReviewContextView execute(UUID reviewId) {
        if (reviewId == null) {
            throw new BusinessException("Identificador da avaliação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        List<ReviewTarget> targets = review.getTargets() != null ? review.getTargets() : List.of();
        Map<UUID, TargetDisplay> displays = targets.isEmpty() ? Map.of()
                : rateableTargetRepository.findDisplaysByIds(targets.stream().map(target -> target.getTargetId()).toList())
                        .stream()
                        .collect(Collectors.toMap(display -> display.id(), Function.identity(), (first, second) -> first));
        List<AdminReviewTargetView> targetViews = targets.stream()
                .map(target -> {
                    TargetDisplay display = displays.get(target.getTargetId());
                    return new AdminReviewTargetView(
                            target.getTargetId(),
                            display != null ? display.type() : null,
                            display != null ? display.displayName() : null,
                            target.getRating(),
                            target.getSpecificComment());
                })
                .toList();

        List<Report> reports = reportRepository.findByReviewId(reviewId);
        long pending = reports.stream().filter(report -> report.getStatus() == ReportStatus.PENDING).count();

        return new AdminReviewContextView(
                new AdminReviewView(
                        review.getId(),
                        review.getExperienceText(),
                        review.getStatus(),
                        review.getVisibility(),
                        review.isAnonymous(),
                        review.isVerifiedOnSite(),
                        review.getCreatedAt(),
                        review.getUpdatedAt(),
                        targetViews),
                pending,
                reports.stream().map(report -> AdminReviewReportView.fromDomain(report)).toList(),
                auditLogRepository.findByReviewId(reviewId).stream().map(log -> AdminReviewAuditView.fromDomain(log)).toList());
    }
}
