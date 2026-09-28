package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.CheckInRepository;
import com.rewit.domain.model.CheckIn;
import com.rewit.infrastructure.persistence.entity.CheckInJpaEntity;
import com.rewit.infrastructure.persistence.repository.CheckInJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade CheckIn (Step 12.0).
 */
@Component
public class CheckInRepositoryAdapter implements CheckInRepository {

    private final CheckInJpaRepository checkInJpaRepository;

    public CheckInRepositoryAdapter(CheckInJpaRepository checkInJpaRepository) {
        this.checkInJpaRepository = Objects.requireNonNull(checkInJpaRepository, "checkInJpaRepository must not be null");
    }

    @Override
    @Transactional
    public CheckIn save(CheckIn checkIn) {
        Objects.requireNonNull(checkIn, "CheckIn cannot be null");
        CheckInJpaEntity entity = CheckInJpaEntity.fromDomain(checkIn);
        CheckInJpaEntity saved = checkInJpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<CheckIn> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return checkInJpaRepository.findById(id).map(e -> toDomain(e));
    }

    @Override
    public Optional<CheckIn> findByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return Optional.empty();
        }
        return checkInJpaRepository.findByReviewId(reviewId).map(e -> toDomain(e));
    }

    @Override
    public List<CheckIn> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return checkInJpaRepository.findByUserId(userId).stream()
                .map(e -> toDomain(e))
                .toList();
    }

    @Override
    public List<CheckIn> findByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return checkInJpaRepository.findByPlaceId(placeId).stream()
                .map(e -> toDomain(e))
                .toList();
    }

    @Override
    public boolean existsByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return false;
        }
        return checkInJpaRepository.existsByReviewId(reviewId);
    }

    private static CheckIn toDomain(CheckInJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
