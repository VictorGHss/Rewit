package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.infrastructure.persistence.entity.DiscussionReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionReportJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    @Override
    @Transactional
    public List<DiscussionReport> saveAll(List<DiscussionReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        return jpaRepository.saveAllAndFlush(reports.stream().map(DiscussionReportJpaEntity::fromDomain).toList())
                .stream().map(DiscussionReportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscussionReport> findPendingByDiscussionId(UUID discussionId) {
        if (discussionId == null) {
            return List.of();
        }
        return jpaRepository.findByDiscussionIdAndStatus(discussionId, ReportStatus.PENDING).stream()
                .map(DiscussionReportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscussionReport> findByDiscussionId(UUID discussionId) {
        if (discussionId == null) {
            return List.of();
        }
        return jpaRepository.findByDiscussionIdOrderByCreatedAtAscIdAsc(discussionId).stream()
                .map(DiscussionReportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId) {
        if (discussionId == null || reporterUserId == null) {
            return false;
        }
        return jpaRepository.existsByDiscussionIdAndReporterUserId(discussionId, reporterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DiscussionReport> findAdminPage(ReportStatus status, ReportReason reason, UUID discussionId,
                                                      int page, int size, String sortDirection) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(new Sort.Order(direction, "createdAt"), new Sort.Order(direction, "id"));
        Page<DiscussionReportJpaEntity> paged = jpaRepository.findAdminPage(status, reason, discussionId,
                PageRequest.of(page, size, sort));
        return PageResult.of(paged.getContent().stream().map(DiscussionReportJpaEntity::toDomain).toList(),
                paged.getNumber(), paged.getSize(), paged.getTotalElements());
    }
}
