package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.infrastructure.persistence.entity.ReviewMediaJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewMediaJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para ReviewMediaRepository implementado com Spring Data JPA.
 */
@Component
public class ReviewMediaRepositoryAdapter implements ReviewMediaRepository {

    private final ReviewMediaJpaRepository reviewMediaJpaRepository;

    public ReviewMediaRepositoryAdapter(ReviewMediaJpaRepository reviewMediaJpaRepository) {
        this.reviewMediaJpaRepository = Objects.requireNonNull(reviewMediaJpaRepository, "ReviewMediaJpaRepository must not be null");
    }

    @Override
    public ReviewMedia save(ReviewMedia media) {
        Objects.requireNonNull(media, "ReviewMedia cannot be null");
        ReviewMediaJpaEntity entity = ReviewMediaJpaEntity.fromDomain(media);
        ReviewMediaJpaEntity saved = reviewMediaJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<ReviewMedia> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return reviewMediaJpaRepository.findById(id)
                .map(entity -> entity.toDomain());
    }

    @Override
    public List<ReviewMedia> findActiveByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return List.of();
        }
        return reviewMediaJpaRepository.findByReviewIdAndStatusOrderByCreatedAtAscIdAsc(reviewId, ReviewMediaStatus.ACTIVE.name())
                .stream()
                .map(entity -> entity.toDomain())
                .toList();
    }

    @Override
    public long countActiveByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return 0;
        }
        return reviewMediaJpaRepository.countByReviewIdAndStatus(reviewId, ReviewMediaStatus.ACTIVE.name());
    }

    @Override
    public List<ReviewMediaReference> findReferencesByObjectKeys(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return List.of();
        }
        // Projeção direta da linha, sem passar pelas invariantes de ReviewMedia: a reconciliação só lê
        return reviewMediaJpaRepository.findByObjectKeyIn(objectKeys)
                .stream()
                .map(entity -> new ReviewMediaReference(
                        entity.getId(),
                        entity.getReviewId(),
                        entity.getObjectKey(),
                        ReviewMediaStatus.valueOf(entity.getStatus()),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()
                ))
                .toList();
    }
}
