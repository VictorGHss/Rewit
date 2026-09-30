package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.infrastructure.persistence.entity.DiscussionJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionJpaRepository;
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
 * Adaptador de persistência para discussões e comentários de avaliações (Step 20.0).
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
    @Transactional(readOnly = true)
    public PageResult<ReviewDiscussion> findActiveByReviewId(UUID reviewId, int page, int size) {
        if (reviewId == null) {
            return PageResult.of(List.of(), page, size, 0L);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Page<DiscussionJpaEntity> paged = discussionJpaRepository.findByReviewIdAndStatus(reviewId, "ACTIVE", pageable);

        List<ReviewDiscussion> domainList = paged.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return PageResult.of(domainList, page, size, paged.getTotalElements());
    }
}
