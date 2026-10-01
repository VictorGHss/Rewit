package com.rewit.application.usecase;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.AdminReportView;
import com.rewit.application.dto.report.ReportDtos.QueryAdminReportsFilter;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Caso de uso para consulta e triagem administrativa da fila de denúncias (Step 26.2).
 *
 * <p>Invariantes e regras aplicadas:
 * 1. Consulta independente de moderação em transação somente leitura (@Transactional(readOnly = true)).
 * 2. Suporte a filtros opcionais por status, reason, reviewId e reporterUserId.
 * 3. Paginação defensiva: default page=0, size=20, limite estrito size <= 100.
 * 4. Ordenação composta determinística: created_at ASC, id ASC (ou created_at DESC, id DESC).
 * 5. Hidratação em lote dos verdadeiros autores internos das avaliações sem N+1 queries.
 * 6. Preservação de privacidade: sem exposição de PII, senhas, IPs ou coordenadas brutas.
 */
@Service
public class QueryAdminReportsUseCase {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;

    public QueryAdminReportsUseCase(ReportRepository reportRepository, ReviewRepository reviewRepository) {
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<AdminReportView> execute(
            Integer page,
            Integer size,
            ReportStatus status,
            ReportReason reason,
            UUID reviewId,
            UUID reporterUserId,
            String sortDirection
    ) {
        return execute(new QueryAdminReportsFilter(page, size, status, reason, reviewId, reporterUserId, sortDirection));
    }

    @Transactional(readOnly = true)
    public PageResult<AdminReportView> execute(QueryAdminReportsFilter filter) {
        QueryAdminReportsFilter effectiveFilter = filter != null ? filter : QueryAdminReportsFilter.ofDefault();

        int page = effectiveFilter.page() != null ? effectiveFilter.page() : DEFAULT_PAGE;
        int size = effectiveFilter.size() != null ? effectiveFilter.size() : DEFAULT_SIZE;

        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size <= 0 || size > MAX_SIZE) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 100", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }

        String sortDirection = effectiveFilter.sortDirection() != null ? effectiveFilter.sortDirection().trim() : "asc";

        // 1. Consulta paginada dos reports com filtros e ordenação determinística
        PageResult<Report> pagedReports = reportRepository.findAllPaged(
                effectiveFilter.status(),
                effectiveFilter.reason(),
                effectiveFilter.reviewId(),
                effectiveFilter.reporterUserId(),
                page,
                size,
                sortDirection
        );

        if (pagedReports.content().isEmpty()) {
            return PageResult.of(List.of(), pagedReports.pageNumber(), pagedReports.pageSize(), pagedReports.totalElements());
        }

        // 2. Carga em lote das reviews associadas para identificar o autor interno sem N+1
        List<UUID> reviewIds = pagedReports.content().stream()
                .map(r -> r.getReviewId())
                .distinct()
                .toList();

        Map<UUID, Review> reviewsById = reviewRepository.findByIdIn(reviewIds).stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r));

        // 3. Projeção DTO para a visão administrativa
        List<AdminReportView> views = pagedReports.content().stream()
                .map(report -> {
                    Review review = reviewsById.get(report.getReviewId());
                    UUID reviewAuthorUserId = review != null ? review.getUserId() : null;
                    ReviewStatus reviewStatus = review != null ? review.getStatus() : null;
                    return new AdminReportView(
                            report.getId(),
                            report.getReviewId(),
                            reviewAuthorUserId,
                            reviewStatus,
                            report.getReporterUserId(),
                            report.getReason(),
                            report.getDetail(),
                            report.getStatus(),
                            report.getCreatedAt(),
                            report.getUpdatedAt()
                    );
                })
                .toList();

        return PageResult.of(views, pagedReports.pageNumber(), pagedReports.pageSize(), pagedReports.totalElements());
    }
}
