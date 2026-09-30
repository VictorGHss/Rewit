package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ReportRepository;
import com.rewit.domain.model.Report;
import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReportJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para denúncias de avaliações (ReportRepository).
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
}
