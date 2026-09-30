package com.rewit.application.port;

import com.rewit.domain.model.ReviewMedia;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída para operações de persistência e recuperação de mídias de avaliação.
 */
public interface ReviewMediaRepository {

    ReviewMedia save(ReviewMedia media);

    Optional<ReviewMedia> findById(UUID id);

    List<ReviewMedia> findActiveByReviewId(UUID reviewId);

    long countActiveByReviewId(UUID reviewId);
}
