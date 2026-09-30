package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ReputationRepository;
import com.rewit.domain.model.UserReputation;
import com.rewit.infrastructure.persistence.repository.UserReputationJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para snapshots de reputação V1 (Step 23.0 / Step 23.1).
 * Implementa a porta {@link ReputationRepository} usando JPA + upsert nativo e lock pessimista.
 */
@Component
public class ReputationRepositoryAdapter implements ReputationRepository {

    private final UserReputationJpaRepository jpaRepository;

    public ReputationRepositoryAdapter(UserReputationJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "jpaRepository must not be null");
    }

    @Override
    @Transactional
    public UserReputation save(UserReputation reputation) {
        Objects.requireNonNull(reputation, "reputation must not be null");
        Instant calculatedAt = reputation.getCalculatedAt() != null
            ? reputation.getCalculatedAt()
            : Instant.now();

        jpaRepository.upsert(
            reputation.getUserId(),
            reputation.getVersion(),
            reputation.getActiveReviews(),
            reputation.getVerifiedReviews(),
            reputation.getHelpfulVotesReceived(),
            reputation.getDistinctTargetsReviewed(),
            calculatedAt
        );

        return new UserReputation(
            reputation.getUserId(),
            reputation.getVersion(),
            reputation.getActiveReviews(),
            reputation.getVerifiedReviews(),
            reputation.getHelpfulVotesReceived(),
            reputation.getDistinctTargetsReviewed(),
            calculatedAt
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserReputation> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return jpaRepository.findById(userId)
            .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional
    public void insertInitialRowIfNotExists(UUID userId) {
        if (userId != null) {
            jpaRepository.insertInitialRowIfNotExists(userId);
        }
    }

    @Override
    @Transactional
    public Optional<UserReputation> findByUserIdForUpdate(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return jpaRepository.findByUserIdForUpdate(userId)
            .map(entity -> entity.toDomain());
    }
}
