package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.infrastructure.persistence.entity.DiscussionReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionReportJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência das denúncias de discussões.
 */
@Component
public class DiscussionReportRepositoryAdapter implements DiscussionReportRepository {

    private final DiscussionReportJpaRepository jpaRepository;

    public DiscussionReportRepositoryAdapter(DiscussionReportJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "DiscussionReportJpaRepository must not be null");
    }

    @Override
    @Transactional
    public DiscussionReport save(DiscussionReport report) {
        Objects.requireNonNull(report, "DiscussionReport cannot be null");
        return jpaRepository.saveAndFlush(DiscussionReportJpaEntity.fromDomain(report)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiscussionReport> findByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId) {
        if (discussionId == null || reporterUserId == null) {
            return Optional.empty();
        }
        return jpaRepository.findByDiscussionIdAndReporterUserId(discussionId, reporterUserId)
                .map(DiscussionReportJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingByDiscussionId(UUID discussionId) {
        if (discussionId == null) {
            return 0;
        }
        return jpaRepository.countByDiscussionIdAndStatus(discussionId, ReportStatus.PENDING);
    }
}
