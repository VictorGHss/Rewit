package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.UserReputation;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA para a tabela {@code user_reputation} (Step 23.0).
 * Mapeia o snapshot derivado de reputação V1.
 */
@Entity
@Table(name = "user_reputation")
public class UserReputationJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "active_reviews", nullable = false)
    private int activeReviews;

    @Column(name = "verified_reviews", nullable = false)
    private int verifiedReviews;

    @Column(name = "helpful_votes_received", nullable = false)
    private int helpfulVotesReceived;

    @Column(name = "distinct_targets_reviewed", nullable = false)
    private int distinctTargetsReviewed;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    protected UserReputationJpaEntity() {}

    public static UserReputationJpaEntity fromDomain(UserReputation domain) {
        UserReputationJpaEntity entity = new UserReputationJpaEntity();
        entity.userId = domain.getUserId();
        entity.version = domain.getVersion();
        entity.activeReviews = domain.getActiveReviews();
        entity.verifiedReviews = domain.getVerifiedReviews();
        entity.helpfulVotesReceived = domain.getHelpfulVotesReceived();
        entity.distinctTargetsReviewed = domain.getDistinctTargetsReviewed();
        entity.calculatedAt = domain.getCalculatedAt();
        return entity;
    }

    public UserReputation toDomain() {
        return new UserReputation(
            userId,
            version,
            activeReviews,
            verifiedReviews,
            helpfulVotesReceived,
            distinctTargetsReviewed,
            calculatedAt
        );
    }

    public UUID getUserId() { return userId; }
    public int getVersion() { return version; }
    public int getActiveReviews() { return activeReviews; }
    public int getVerifiedReviews() { return verifiedReviews; }
    public int getHelpfulVotesReceived() { return helpfulVotesReceived; }
    public int getDistinctTargetsReviewed() { return distinctTargetsReviewed; }
    public Instant getCalculatedAt() { return calculatedAt; }
}
