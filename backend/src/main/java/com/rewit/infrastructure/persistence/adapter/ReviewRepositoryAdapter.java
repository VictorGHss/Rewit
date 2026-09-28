package com.rewit.infrastructure.persistence.adapter;

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
    public boolean existsById(UUID id) {
        if (id == null) {
            return false;
        }
        return reviewJpaRepository.existsById(id);
    }

    private static ReviewTarget toDomain(ReviewTargetJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
