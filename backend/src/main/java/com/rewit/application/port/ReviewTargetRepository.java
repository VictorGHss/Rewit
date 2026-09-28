package com.rewit.application.port;

import com.rewit.domain.model.ReviewTarget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação de alvos avaliados (ReviewTarget).
 */
public interface ReviewTargetRepository {

    ReviewTarget save(ReviewTarget target);

    List<ReviewTarget> saveAll(List<ReviewTarget> targets);

    Optional<ReviewTarget> findById(UUID id);

    List<ReviewTarget> findByReviewId(UUID reviewId);

    boolean existsByReviewIdAndTargetId(UUID reviewId, UUID targetId);
}
