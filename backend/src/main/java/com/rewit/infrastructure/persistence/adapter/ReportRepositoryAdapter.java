package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.ReportRepository;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.Report;
import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReportJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para denúncias de avaliações (ReportRepository) (Step 19.0 / Step 26.2).
 */
@Component
public class ReportRepositoryAdapter implements ReportRepository {

    private final ReportJpaRepository reportJpaRepository;

    public ReportRepositoryAdapter(ReportJpaRepository reportJpaRepository) {
        this.reportJpaRepository = Objects.requireNonNull(reportJpaRepository, "ReportJpaRepository must not be null");
    }

    @Override
    @Transactional
    public Report save(Report report) {
        Objects.requireNonNull(report, "Report cannot be null");
        ReportJpaEntity entity = ReportJpaEntity.fromDomain(report);
        ReportJpaEntity saved = reportJpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional
    public List<Report> saveAll(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        List<ReportJpaEntity> entities = reports.stream()
                .map(r -> ReportJpaEntity.fromDomain(r))
                .toList();
        List<ReportJpaEntity> saved = reportJpaRepository.saveAllAndFlush(entities);
        return saved.stream()
                .map(e -> e.toDomain())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Report> findByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId) {
        if (reviewId == null || reporterUserId == null) {
            return Optional.empty();
        }
        return reportJpaRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)
                .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Report> findPendingByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return List.of();
        }
        return reportJpaRepository.findPendingByReviewId(reviewId).stream()
                .map(entity -> entity.toDomain())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return 0;
        }
        return reportJpaRepository.countPendingByReviewId(reviewId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId) {
        if (reviewId == null || reporterUserId == null) {
            return false;
        }
        return reportJpaRepository.existsByReviewIdAndReporterUserId(reviewId, reporterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Report> findAllPaged(
            ReportStatus status,
            ReportReason reason,
            UUID reviewId,
            UUID reporterUserId,
            int page,
            int size,
            String sortDirection
    ) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(
                new Sort.Order(direction, "createdAt"),
                new Sort.Order(direction, "id")
        );
        Pageable pageable = PageRequest.of(page, size, sort);

        String statusStr = status != null ? status.name() : null;
        String reasonStr = reason != null ? reason.name() : null;

        Page<ReportJpaEntity> jpaPage = reportJpaRepository.findAdminReports(statusStr, reasonStr, reviewId, reporterUserId, pageable);
        List<Report> content = jpaPage.getContent().stream()
                .map(e -> e.toDomain())
                .toList();

        return PageResult.of(content, jpaPage.getNumber(), jpaPage.getSize(), jpaPage.getTotalElements());
    }
}

