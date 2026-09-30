package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade raiz de agregado Review.
 */
@Component
public class ReviewRepositoryAdapter implements ReviewRepository {

    private final ReviewJpaRepository reviewJpaRepository;
    private final ReviewTargetJpaRepository reviewTargetJpaRepository;

    public ReviewRepositoryAdapter(ReviewJpaRepository reviewJpaRepository,
                                   ReviewTargetJpaRepository reviewTargetJpaRepository) {
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository, "ReviewJpaRepository must not be null");
        this.reviewTargetJpaRepository = Objects.requireNonNull(reviewTargetJpaRepository, "ReviewTargetJpaRepository must not be null");
    }

    @Override
    @Transactional
    public Review save(Review review) {
        Objects.requireNonNull(review, "Review cannot be null");

        ReviewJpaEntity savedEntity = reviewJpaRepository.findById(review.getId())
                .map(existing -> {
                    existing.updateFromDomain(review);
                    return reviewJpaRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> {
                    ReviewJpaEntity newEntity = ReviewJpaEntity.fromDomain(review);
                    return reviewJpaRepository.saveAndFlush(newEntity);
                });

        return savedEntity.toDomain(review.getTargets());
    }

    @Override
    public Optional<Review> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return reviewJpaRepository.findById(id)
                .map(entity -> {
                    List<ReviewTarget> targets = reviewTargetJpaRepository.findByReviewId(id).stream()
                            .map(ReviewRepositoryAdapter::toDomain)
                            .filter(Objects::nonNull)
                            .toList();
                    return entity.toDomain(targets);
                });
    }

    @Override
    public List<Review> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return reviewJpaRepository.findByUserId(userId).stream()
                .map(entity -> {
                    List<ReviewTarget> targets = reviewTargetJpaRepository.findByReviewId(entity.getId()).stream()
                            .map(ReviewRepositoryAdapter::toDomain)
                            .filter(Objects::nonNull)
                            .toList();
                    return entity.toDomain(targets);
                })
                .toList();
    }

    @Override
    public List<Review> findByContextPlaceId(UUID contextPlaceId) {
        if (contextPlaceId == null) {
            return List.of();
        }
        return reviewJpaRepository.findByContextPlaceId(contextPlaceId).stream()
                .map(entity -> {
                    List<ReviewTarget> targets = reviewTargetJpaRepository.findByReviewId(entity.getId()).stream()
                            .map(ReviewRepositoryAdapter::toDomain)
                            .filter(Objects::nonNull)
                            .toList();
                    return entity.toDomain(targets);
                })
                .toList();
    }

    @Override
    public PageResult<Review> findByUserIdPaged(UUID userId, int page, int size) {
        if (userId == null) {
            return PageResult.of(List.of(), page, size, 0);
        }
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("createdAt"), org.springframework.data.domain.Sort.Order.asc("id"))
        );
        org.springframework.data.domain.Page<ReviewJpaEntity> entityPage = reviewJpaRepository.findByUserId(userId, pageRequest);

        List<Review> reviews = entityPage.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return PageResult.of(reviews, page, size, entityPage.getTotalElements());
    }

    @Override
    public PageResult<ReviewWithTarget> findByTarget(
            UUID targetId,
            UUID requesterUserId,
            boolean verifiedOnly,
            String sort,
            int page,
            int size
    ) {
        if (targetId == null) {
            return PageResult.of(List.of(), page, size, 0);
        }

        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(page, size);
        String normalizedSort = (sort != null && !sort.isBlank()) ? sort.trim().toLowerCase(java.util.Locale.ROOT) : "newest";

        org.springframework.data.domain.Page<Object[]> resultPage;
        switch (normalizedSort) {
            case "rating_desc" -> resultPage = reviewJpaRepository.findReviewsByTargetRatingDesc(targetId, requesterUserId, verifiedOnly, pageRequest);
            case "rating_asc" -> resultPage = reviewJpaRepository.findReviewsByTargetRatingAsc(targetId, requesterUserId, verifiedOnly, pageRequest);
            default -> resultPage = reviewJpaRepository.findReviewsByTargetNewest(targetId, requesterUserId, verifiedOnly, pageRequest);
        }

        List<ReviewWithTarget> items = resultPage.getContent().stream()
                .map(row -> {
                    ReviewJpaEntity reviewEntity = (ReviewJpaEntity) row[0];
                    ReviewTargetJpaEntity targetEntity = (ReviewTargetJpaEntity) row[1];
                    Review review = reviewEntity.toDomain();
                    ReviewTarget target = targetEntity.toDomain();
                    return new ReviewWithTarget(review, target);
                })
                .toList();

        return PageResult.of(items, page, size, resultPage.getTotalElements());
    }

    @Override
    public boolean existsById(UUID id) {
        if (id == null) {
            return false;
        }
        return reviewJpaRepository.existsById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Review> findFeedByFollowing(UUID requesterUserId, int page, int size) {
        if (requesterUserId == null) {
            return PageResult.of(List.of(), page, size, 0);
        }
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("createdAt"),
                        org.springframework.data.domain.Sort.Order.asc("id")
                )
        );
        org.springframework.data.domain.Page<ReviewJpaEntity> entityPage = reviewJpaRepository.findFeedByFollowing(requesterUserId, pageRequest);

        List<Review> reviews = entityPage.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return PageResult.of(reviews, page, size, entityPage.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveByUserId(UUID userId) {
        if (userId == null) {
            return 0L;
        }
        return reviewJpaRepository.countByUserIdAndStatus(userId, "ACTIVE");
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveVerifiedByUserId(UUID userId) {
        if (userId == null) {
            return 0L;
        }
        return reviewJpaRepository.countByUserIdAndStatusAndIsVerifiedOnSiteTrue(userId, "ACTIVE");
    }

    private static ReviewTarget toDomain(ReviewTargetJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
