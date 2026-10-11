package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.infrastructure.persistence.entity.BusinessResponseJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessResponseJpaRepository extends JpaRepository<BusinessResponseJpaEntity, UUID> {

    /** Atendida por uq_business_responses_review_active. */
    Optional<BusinessResponseJpaEntity> findByReviewIdAndDiscussionIdIsNullAndBusinessAccountIdAndStatus(
            UUID reviewId, UUID businessAccountId, BusinessResponseStatus status);

    /** Atendida por uq_business_responses_discussion_active. */
    Optional<BusinessResponseJpaEntity> findByReviewIdAndDiscussionIdAndBusinessAccountIdAndStatus(
            UUID reviewId, UUID discussionId, UUID businessAccountId, BusinessResponseStatus status);

    /** Atendida por idx_business_responses_review (review_id, created_at, id). */
    Page<BusinessResponseJpaEntity> findByReviewIdAndDiscussionIdIsNullAndStatusOrderByCreatedAtAscIdAsc(
            UUID reviewId, BusinessResponseStatus status, Pageable pageable);

    List<BusinessResponseJpaEntity> findByDiscussionIdInAndStatusOrderByCreatedAtAscIdAsc(
            Collection<UUID> discussionIds, BusinessResponseStatus status);
}
