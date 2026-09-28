package com.rewit.application.port;

import com.rewit.domain.model.Review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da raiz de agregado Review.
 */
public interface ReviewRepository {

    Review save(Review review);

    Optional<Review> findById(UUID id);

    List<Review> findByUserId(UUID userId);

    List<Review> findByContextPlaceId(UUID contextPlaceId);

    boolean existsById(UUID id);
}
