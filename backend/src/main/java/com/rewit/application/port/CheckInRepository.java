package com.rewit.application.port;

import com.rewit.domain.model.CheckIn;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da entidade CheckIn (Step 12.0).
 */
public interface CheckInRepository {

    CheckIn save(CheckIn checkIn);

    Optional<CheckIn> findById(UUID id);

    Optional<CheckIn> findByReviewId(UUID reviewId);

    List<CheckIn> findByUserId(UUID userId);

    List<CheckIn> findByPlaceId(UUID placeId);

    boolean existsByReviewId(UUID reviewId);
}
