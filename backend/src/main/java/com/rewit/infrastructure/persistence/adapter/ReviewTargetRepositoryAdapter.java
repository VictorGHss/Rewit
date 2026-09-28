package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade ReviewTarget.
 */
@Component
public class ReviewTargetRepositoryAdapter implements ReviewTargetRepository {

    private final ReviewTargetJpaRepository reviewTargetJpaRepository;

    public ReviewTargetRepositoryAdapter(ReviewTargetJpaRepository reviewTargetJpaRepository) {
        this.reviewTargetJpaRepository = Objects.requireNonNull(reviewTargetJpaRepository, "ReviewTargetJpaRepository must not be null");
    }

    @Override
    @Transactional
    public ReviewTarget save(ReviewTarget target) {
        Objects.requireNonNull(target, "ReviewTarget cannot be null");
        ReviewTargetJpaEntity entity = toEntity(target);
        ReviewTargetJpaEntity saved = reviewTargetJpaRepository.saveAndFlush(Objects.requireNonNull(entity, "ReviewTargetJpaEntity cannot be null"));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public List<ReviewTarget> saveAll(List<ReviewTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<ReviewTargetJpaEntity> entities = targets.stream()
                .map(ReviewTargetRepositoryAdapter::toEntity)
                .filter(Objects::nonNull)
                .toList();
        return reviewTargetJpaRepository.saveAllAndFlush(entities).stream()
                .map(ReviewTargetRepositoryAdapter::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<ReviewTarget> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return reviewTargetJpaRepository.findById(id).map(ReviewTargetRepositoryAdapter::toDomain);
    }

    @Override
    public List<ReviewTarget> findByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return List.of();
        }
        return reviewTargetJpaRepository.findByReviewId(reviewId).stream()
                .map(ReviewTargetRepositoryAdapter::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ReviewTarget> findByReviewIdIn(java.util.Collection<UUID> reviewIds) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            return List.of();
        }
        return reviewTargetJpaRepository.findByReviewIdIn(reviewIds).stream()
                .map(ReviewTargetRepositoryAdapter::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public boolean existsByReviewIdAndTargetId(UUID reviewId, UUID targetId) {
        if (reviewId == null || targetId == null) {
            return false;
        }
        return reviewTargetJpaRepository.existsByReviewIdAndTargetId(reviewId, targetId);
    }

    private static ReviewTarget toDomain(ReviewTargetJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }

    private static ReviewTargetJpaEntity toEntity(ReviewTarget domain) {
        return domain != null ? ReviewTargetJpaEntity.fromDomain(domain) : null;
    }
}
