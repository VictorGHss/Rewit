package com.rewit.application.usecase;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.ReviewDiscussion;
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
 * Fila administrativa de denúncias de discussões, paginada e filtrável, com o contexto das discussões
 * carregado em lote (sem N+1).
 */
@Service
public class QueryAdminDiscussionReportsUseCase {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final DiscussionReportRepository discussionReportRepository;
    private final DiscussionRepository discussionRepository;

    public QueryAdminDiscussionReportsUseCase(DiscussionReportRepository discussionReportRepository,
                                              DiscussionRepository discussionRepository) {
        this.discussionReportRepository = Objects.requireNonNull(discussionReportRepository, "DiscussionReportRepository must not be null");
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<AdminDiscussionReportView> execute(int page, int size, ReportStatus status, ReportReason reason,
                                                         UUID discussionId, String sortDirection) {
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 100", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }
        String direction = "desc".equalsIgnoreCase(sortDirection) ? "desc" : "asc";

        PageResult<DiscussionReport> reports = discussionReportRepository.findAdminPage(status, reason, discussionId, page, size, direction);
        Map<UUID, ReviewDiscussion> discussions = discussionRepository
                .findAllByIds(reports.content().stream().map(DiscussionReport::getDiscussionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ReviewDiscussion::getId, Function.identity()));

        List<AdminDiscussionReportView> views = reports.content().stream()
                .map(report -> toView(report, discussions.get(report.getDiscussionId())))
                .toList();
        return PageResult.of(views, reports.pageNumber(), reports.pageSize(), reports.totalElements());
    }

    private static AdminDiscussionReportView toView(DiscussionReport report, ReviewDiscussion discussion) {
        return new AdminDiscussionReportView(
                report.getId(),
                report.getDiscussionId(),
                discussion != null ? discussion.getReviewId() : null,
                discussion != null ? discussion.getParentId() : null,
                discussion != null ? discussion.getUserId() : null,
                discussion != null ? discussion.getStatus() : null,
                report.getReporterUserId(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}
