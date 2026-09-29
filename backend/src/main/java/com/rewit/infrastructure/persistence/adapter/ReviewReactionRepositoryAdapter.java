package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador de persistência para validação de utilidade (Helpful) em avaliações (ReviewReactionRepository).
 */
@Component
public class ReviewReactionRepositoryAdapter implements ReviewReactionRepository {

    private static final String HELPFUL_TYPE = "HELPFUL";

    private final ReviewReactionJpaRepository reviewReactionJpaRepository;

    public ReviewReactionRepositoryAdapter(ReviewReactionJpaRepository reviewReactionJpaRepository) {
        this.reviewReactionJpaRepository = reviewReactionJpaRepository;
    }

    @Override
    @Transactional
    public boolean addHelpful(UUID reviewId, UUID userId) {
        if (reviewId == null || userId == null) {
            return false;
        }
        int rows = reviewReactionJpaRepository.insertHelpfulIfNotExists(
                UUID.randomUUID(),
                reviewId,
                userId,
                Instant.now()
        );
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean removeHelpful(UUID reviewId, UUID userId) {
        if (reviewId == null || userId == null) {
            return false;
        }
        boolean existed = reviewReactionJpaRepository.existsByReviewIdAndUserIdAndReactionType(reviewId, userId, HELPFUL_TYPE);
        reviewReactionJpaRepository.deleteByReviewIdAndUserIdAndReactionType(reviewId, userId, HELPFUL_TYPE);
        return existed;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHelpful(UUID reviewId, UUID userId) {
        if (reviewId == null || userId == null) {
            return false;
        }
        return reviewReactionJpaRepository.existsByReviewIdAndUserIdAndReactionType(reviewId, userId, HELPFUL_TYPE);
    }

    @Override
    @Transactional(readOnly = true)
    public long countHelpful(UUID reviewId) {
        if (reviewId == null) {
            return 0L;
        }
        return reviewReactionJpaRepository.countByReviewIdAndReactionType(reviewId, HELPFUL_TYPE);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countHelpfulByReviewIds(Collection<UUID> reviewIds) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = reviewReactionJpaRepository.countHelpfulByReviewIds(reviewIds);
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            Long count = ((Number) row[1]).longValue();
            counts.put(id, count);
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findHelpfulReviewIdsByUser(Collection<UUID> reviewIds, UUID userId) {
        if (reviewIds == null || reviewIds.isEmpty() || userId == null) {
            return Set.of();
        }
        List<UUID> ids = reviewReactionJpaRepository.findHelpfulReviewIdsByUser(reviewIds, userId);
        return new HashSet<>(ids);
    }
}
