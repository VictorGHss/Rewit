package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.infrastructure.persistence.entity.DiscussionJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para discussões e comentários de avaliações (Step 20.0 / Step 32.0).
 */
@Component
public class DiscussionRepositoryAdapter implements DiscussionRepository {

    private final DiscussionJpaRepository discussionJpaRepository;

    public DiscussionRepositoryAdapter(DiscussionJpaRepository discussionJpaRepository) {
        this.discussionJpaRepository = Objects.requireNonNull(discussionJpaRepository, "DiscussionJpaRepository must not be null");
    }

    @Override
    @Transactional
    public ReviewDiscussion save(ReviewDiscussion discussion) {
        Objects.requireNonNull(discussion, "ReviewDiscussion cannot be null");
        DiscussionJpaEntity entity = DiscussionJpaEntity.fromDomain(discussion);
        DiscussionJpaEntity saved = discussionJpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDiscussion> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return discussionJpaRepository.findById(id)
                .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional
    public Optional<ReviewDiscussion> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return discussionJpaRepository.findByIdForUpdate(id)
                .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReviewDiscussion> findActiveByReviewId(UUID reviewId, int page, int size) {
        if (reviewId == null) {
            return PageResult.of(List.of(), page, size, 0L);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Page<DiscussionJpaEntity> paged = discussionJpaRepository.findByReviewIdAndStatus(reviewId, DiscussionStatus.ACTIVE, pageable);

        List<ReviewDiscussion> domainList = paged.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return PageResult.of(domainList, page, size, paged.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReviewDiscussion> findThreadRootsVisibleTo(UUID reviewId, UUID viewerId, int page, int size) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        Page<DiscussionJpaEntity> paged = discussionJpaRepository.findThreadRootsVisibleTo(reviewId, viewerId,
                DiscussionStatus.ACTIVE, DiscussionStatus.UNDER_REVIEW, DiscussionStatus.REMOVED, chronological(page, size));
        return PageResult.of(toDomain(paged.getContent()), page, size, paged.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDiscussion> findFirstRepliesVisibleTo(Collection<UUID> rootIds, UUID viewerId, int limitPerRoot) {
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        if (rootIds == null || rootIds.isEmpty() || limitPerRoot <= 0) {
            return List.of();
        }
        return toDomain(discussionJpaRepository.findFirstRepliesVisibleTo(rootIds, viewerId, limitPerRoot));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countRepliesVisibleTo(Collection<UUID> rootIds, UUID viewerId) {
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        if (rootIds == null || rootIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : discussionJpaRepository.countRepliesVisibleTo(rootIds, viewerId,
                DiscussionStatus.ACTIVE, DiscussionStatus.UNDER_REVIEW)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReviewDiscussion> findRepliesVisibleTo(UUID rootId, UUID viewerId, int page, int size) {
        Objects.requireNonNull(rootId, "rootId must not be null");
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        Page<DiscussionJpaEntity> paged = discussionJpaRepository.findRepliesVisibleTo(rootId, viewerId,
                DiscussionStatus.ACTIVE, DiscussionStatus.UNDER_REVIEW, chronological(page, size));
        return PageResult.of(toDomain(paged.getContent()), page, size, paged.getTotalElements());
    }

    private static Pageable chronological(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    private static List<ReviewDiscussion> toDomain(List<DiscussionJpaEntity> entities) {
        return entities.stream().map(DiscussionJpaEntity::toDomain).toList();
    }
}
