package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da raiz de agregado Review.
 */
public interface ReviewRepository {

    record ReviewWithTarget(Review review, ReviewTarget target) {}

    Review save(Review review);

    Optional<Review> findById(UUID id);

    List<Review> findByIdIn(java.util.Collection<UUID> ids);

    Optional<Review> findByIdForUpdate(UUID id);

    List<Review> findByUserId(UUID userId);

    PageResult<Review> findByUserIdPaged(UUID userId, int page, int size);

    List<Review> findByContextPlaceId(UUID contextPlaceId);

    PageResult<ReviewWithTarget> findByTarget(
            UUID targetId,
            UUID requesterUserId,
            boolean verifiedOnly,
            String sort,
            int page,
            int size
    );

    boolean existsById(UUID id);

    PageResult<Review> findFeedByFollowing(UUID requesterUserId, int page, int size);

    long countActiveByUserId(UUID userId);

    long countActiveVerifiedByUserId(UUID userId);
}
